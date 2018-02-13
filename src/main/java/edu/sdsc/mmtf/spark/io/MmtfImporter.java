package edu.sdsc.mmtf.spark.io;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.zip.GZIPInputStream;

import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.PairFunction;
import org.biojava.nbio.structure.Chain;
import org.biojava.nbio.structure.EntityInfo;
import org.biojava.nbio.structure.EntityType;
import org.biojava.nbio.structure.Structure;
import org.biojava.nbio.structure.io.MMCIFFileReader;
import org.biojava.nbio.structure.io.PDBFileParser;
import org.biojava.nbio.structure.io.mmtf.MmtfStructureWriter;
import org.rcsb.mmtf.api.StructureDataInterface;
import org.rcsb.mmtf.encoder.AdapterToStructureData;

import scala.Tuple2;

/**
 * Methods for importing and downloading macromolecular structures from external
 * resources, such as homology or de novo models.
 * 
 * The data are returned as a JavaPairRDD with 
 * the structure id as the key and the structural data as the value.
 * 
 * <p>
 * Supported operations and file formats:
 * <p>
 * <ul>
 * <li>import directory of PDB files (.pdb,, pdb.gz, .ent, .ent.gz)
 * <li>import directory of mmCIF files (.cif, .cif.gz)
 * <li>import homology models from SWISS-MODEL repository (local copy)
 * <li>download homology models from SWISS-MODEL by UniProtIds
 * <li>download homology models from SWISS-MODEL by Urls
 * </ul>
 * 
 * @author Peter Rose
 * @author Yue Yu
 * @since 0.2.0
 *
 */
public class MmtfImporter implements Serializable {
    private static final long serialVersionUID = 4998053042712120399L;
    private static final String SWISS_PROT_REST_URL = "https://swissmodel.expasy.org/repository/uniprot/";
    private static final String SWISS_MODEL_PROVIDER = ".pdb?provider=swissmodel";

    /**
     * Reads uncompressed and compressed PDB files recursively from a given
     * directory path. This method reads files with the following file
     * extensions: <code>.pdb, .pdb.gz, .ent, .ent.gz</code>
     * 
     * <p>
     * NOTE: This method is not intended to read PDB files from the Protein Data
     * Bank! The PDB file format is a legacy file format and its use should be
     * avoided. Use the methods in {@link edu.sdsc.mmtf.spark.io.MmtfReader} to
     * read or download structures accurately and efficiently from the
     * Protein Data Bank (PDB).
     * 
     * <p>
     * This method can read some types of non-standard PDB file, e.g., those
     * generated by Rosetta (with wrapped atom names), or PDB files with missing
     * SEQRES records.
     * 
     * <p>
     * Depending on the input data, the generated data model may be partially
     * incomplete.
     * 
     * @param path
     *            Path to uncompressed or compressed PDB files
     * @param sc
     *            Spark context
     * @return structure data with the file path as the key and the structure as
     *         the value
     */
    public static JavaPairRDD<String, StructureDataInterface> importPdbFiles(String path, JavaSparkContext sc) {
        return sc.parallelize(getFiles(path))
                .mapToPair(
                        t -> new Tuple2<String, StructureDataInterface>(t.toString(), getFromPdbFile(t, t.toString())))
                .filter(t -> t._2 != null);
    }
  
    /**
     * Reads uncompressed and compressed mmCIF files recursively from a given
     * directory path. This methods reads files with the .cif or .cif.gz
     * extension.
     * 
     * @param path
     *            Path to .cif files
     * @param sc
     *            Spark context
     * @return structure data as keyword/value pairs
     */
    public static JavaPairRDD<String, StructureDataInterface> importMmcifFiles(String path, JavaSparkContext sc) {
        return sc.parallelize(getFiles(path)).mapToPair(new PairFunction<File, String, StructureDataInterface>() {
            private static final long serialVersionUID = -7815663658405168429L;

            public Tuple2<String, StructureDataInterface> call(File f) throws Exception {
                InputStream is = null;

                String path = f.getName();
                if (path.endsWith(".cif") || path.endsWith((".cif.gz"))) {

                    try {
                        is = new FileInputStream(f);
                        if (path.endsWith(".cif.gz")) {
                            is = new GZIPInputStream(is);
                        }
                    } catch (Exception e) {
                        return null;
                    }

                    // parse .cif file
                    MMCIFFileReader mmcifReader = new MMCIFFileReader();
                    Structure struc = mmcifReader.getStructure(is);
                    is.close();

                    // convert to mmtf
                    AdapterToStructureData writerToEncoder = new AdapterToStructureData();
                    new MmtfStructureWriter(struc, writerToEncoder);

                    return new Tuple2<String, StructureDataInterface>(
                            path.substring(0, path.indexOf(".cif")), writerToEncoder);
                } else {
                    return null;
                }
            }
        }).filter(t -> t != null);
    }

    /**
     * Reads homology models from the SWISS-MODEL repository.
     * 
     * <p>SWISS-PROT repositories for specific species can be downloaded from
     * the <a href="https://swissmodel.expasy.org/repository">SWISS-MODEL Repository</a>
     * 
     * <p>References:
     * <p>
     * Bienert S, Waterhouse A, de Beer TA, Tauriello G, Studer G, Bordoli L,
     * Schwede T (2017). The SWISS-MODEL Repository - new features and
     * functionality, Nucleic Acids Res. 45(D1):D313-D319.
     * <a href="https://dx.doi.org/10.1093/nar/gkw1132">doi:10.1093/nar/gkw1132</a>.
     * 
     * <p>
     * Biasini M, Bienert S, Waterhouse A, Arnold K, Studer G, Schmidt T, Kiefer F,
     * Gallo Cassarino T, Bertoni M, Bordoli L, Schwede T(2014). The SWISS-MODEL
     * Repository - modelling protein tertiary and quaternary structure using
     * evolutionary information, Nucleic Acids Res. 42(W1):W252–W258.
     * <a href="https://doi.org/10.1093/nar/gku340">doi:10.1093/nar/gku340</a>.
     * 
     * @param path
     *            Path to SWISS-MODEL repository
     * @param sc
     *            Spark context
     * @return structure data  key = UniProtId, value = structure
     */
    public static JavaPairRDD<String, StructureDataInterface> importSwissModelRepository(String path, JavaSparkContext sc) {
        return sc.parallelize(getFiles(path))
                .mapToPair(t -> new Tuple2<String, AdapterToStructureData>(
                        getUniProtIdFromPath(t.toString()), getFromPdbFile(t, getUniProtIdFromPath(t.toString()))))
                .filter(t -> t._2 != null)
                .mapValues(v -> setSwissModelHeader(v));
    }
    
    /**
     * Downloads SWISS-MODEL homology models for a list of UniProtIds. 
     * Non-matching UniProtIds are ignored.
     * 
     * <p>Example
     * <pre>
     * {@code
     * List<String> uniProtIds = Arrays.asList("P36575","P24539","O00244","P18846","Q9UII2");
     * JavaPairRDD<String, StructureDataInterface> structures = MmtfImporter.downloadSwissModels(uniProtIds, sc);
     * }
     * </pre></code>
     * 
     * @param uniProtIds
     *            List of UniProtIds (upper case)
     * @param sc
     *            Spark context
     * @return structure data  key = UniProtId, value = structure
     * @see <a href="https://swissmodel.expasy.org/docs/repository_help#smr_api"">SWISS-MODEL API</a>.
     */
    public static JavaPairRDD<String, StructureDataInterface> downloadSwissModelsByUniProtIds(List<String> uniProtIds,
            JavaSparkContext sc) {
        return sc.parallelize(uniProtIds)
                .mapToPair(t -> new Tuple2<String, AdapterToStructureData>(
                t,
                getFromPdbUrl(SWISS_PROT_REST_URL + t + SWISS_MODEL_PROVIDER, t)))
                .filter(t -> t._2 != null)
                .mapValues(v -> setSwissModelHeader(v));
    }

    /**
     * Downloads homology models for a list of SWISS-MODEL URLs.
     * The URLs can be extracted from datasets, 
     * see {@link edu.sdsc.mmtf.spark.datasets.SwissModelDataset}.
     * 
     * <p>Example: download and filter a SWISS-MODEL dataset,
     * then use the URLs to retrieve the models.
     * <pre>
     * {@code
     * List<String> uniProtIds = Arrays.asList("P36575","P24539","O00244");
     * Dataset<Row> ds = SwissModelDataset.getSwissModels(uniProtIds);
     * 
     * ds = ds.filter("qmean > -2.5 AND coverage > 0.9");
     * 
     * List<String> urls = ds.select("coordinates").as(Encoders.STRING()).collectAsList();
     * JavaPairRDD<String, StructureDataInterface> models = MmtfImporter.downloadSwissModelsByUrls(urls, sc);
     * }
     * </pre>
     * 
     * @param urls
     *            list of URLs to models in the SWISS-MODEL repository
     * @param sc
     *            Spark context
     * @return structure data  key = UniProtId, value = structure
     */
    public static JavaPairRDD<String, StructureDataInterface> downloadSwissModelsByUrls(List<String> urls, JavaSparkContext sc) {
        return sc.parallelize(urls)
                .mapToPair(t -> new Tuple2<String, AdapterToStructureData>(
                        getUniProtIdFromUrl(t.toString()), getFromPdbUrl(t, getUniProtIdFromUrl(t.toString()))))
                .filter(t -> t._2 != null)
                .mapValues(v -> setSwissModelHeader(v));
    }
    
    /**
     * Reads a PDB-formatted String.
     * 
     * @param pdbString a PDB-formatted String
     * @param structureId the structure identifier
     * @return structure data
     * @throws IOException
     */
    public static StructureDataInterface getFromPdbString(String pdbString, String structureId) throws IOException {
        StructureDataInterface structure = null;
        InputStream is = new ByteArrayInputStream(pdbString.getBytes());
        structure = toStructureDataInterface(is, structureId);
        is.close();

        return structure;
    }
    
    /**
     * Sets SWISS-MODEL specific metadata.
     * 
     * @param structure
     * @return
     */
    private static StructureDataInterface setSwissModelHeader(AdapterToStructureData structure) {
        if (structure == null) return structure;
        
        String title = "SWISS-MODEL " + structure.getStructureId();
        String date = structure.getReleaseDate();
        String[] experimentalMethods = new String[]{"THEORETICAL MODEL"};
        
        // set title, dates, and experimental methods 
        structure.setHeaderInfo(1f, 1f, 99f, title, date, date, experimentalMethods);
        return structure;
    }
    
    /**
     * Extracts the UniProtId from the model filename in the SWISS-MODEL repository.
     * 
     * <p>Example:
     * ".../SWISS-MODEL_Repository/O3/25/28/swissmodel/" -> UniProtId: O32528
     * 
     * @param path
     * @return
     */
    private static String getUniProtIdFromPath(String path) {
        int i = path.indexOf("swissmodel");
        return path.substring(i-9, i-7) 
                + path.substring(i-6, i-4) 
                + path.substring(i-3, i-1);
    }
    
    /**
     * Extracts the UniProtId from the model filename in the SWISS-MODEL repository.
     * 
     * <p>Example:
     * ".../SWISS-MODEL_Repository/O3/25/28/swissmodel/" -> UniProtId: O32528
     * 
     * @param path
     * @return
     */
    private static String getUniProtIdFromUrl(String path) {
        int i = path.indexOf(".pdb");
        return path.substring(i-6, i);
    }
    
    /**
     * Reads a PDB file from a file system.
     * 
     * @param
     * @return
     * @throws IOException
     */
    private static AdapterToStructureData getFromPdbFile(File file, String structureId) throws IOException {
        AdapterToStructureData structure = null;
        InputStream is = null;

        String path = file.toString();
        if (path.endsWith(".pdb.gz") || path.endsWith(".ent.gz")) {
            is = new GZIPInputStream(new FileInputStream(file));
            structure = toStructureDataInterface(is, structureId);
        } else if (path.endsWith(".pdb") || path.endsWith(".ent")) {
            is = new FileInputStream(file);
            structure = toStructureDataInterface(is, structureId);
        } else {
            return null;
        }
        is.close();

        return structure;
    }
 
    /**
     * Reads a PDB file from a URL.
     * 
     * @param uniProtId
     * @return
     * @throws IOException
     */
    private static AdapterToStructureData getFromPdbUrl(String url, String structureId) throws IOException {
        URL u = new URL(url);
        InputStream is = null;
        try {
            is = u.openStream();
        } catch (IOException e) {
            return null;
        }
        AdapterToStructureData structure = toStructureDataInterface(is, structureId);
        is.close();

        return structure;
    }

    /**
     * Parses PDB-formatted input stream and return structure data.
     * 
     * @param inputStream PDB-formatted input stream
     * @param structureId id to be assigned to the structure
     * @return structure data
     * @throws IOException
     */
    private static AdapterToStructureData toStructureDataInterface(InputStream inputStream, String structureId)
            throws IOException {
        
        // standardize PDB formatting
        InputStream pdbIs = standardizePdbInputStream(inputStream);

        // parse PDB and generate BioJava Structure object
        PDBFileParser parser = new PDBFileParser();
        parser.getFileParsingParameters().setCreateAtomBonds(true);
        Structure struct = parser.parsePDBFile(pdbIs);
        struct.setPDBCode(structureId);

        // temporary workaround to a bug in BioJava
        // where the entity info is null when there is only one polymer chain.
        for (EntityInfo info: struct.getEntityInfos()) {
            if (info.getType() == null) {
                for (String chainId: info.getChainIds()) {
                    Chain c =struct.getChain(chainId);
                    if (c.getAtomSequence().length() > 0) {
                        info.setType(EntityType.POLYMER);
                    }
                }
            }
        }

        pdbIs.close();

        // convert to MMTF
        AdapterToStructureData writerToEncoder = new AdapterToStructureData();
        // TODO get version number
        writerToEncoder.setMmtfProducer("mmtf-spark 0.2.0");
        new MmtfStructureWriter(struct, writerToEncoder);

        return writerToEncoder;
    }

    /**
     * Standardizes a PDB-formatted input stream. This method adds missing
     * SEQRES records and fixes "wrapped" hydrogen atom names (e.g., those found
     * in ROSETTA PDB file).
     * 
     * @param inputStream
     *            original PDB-formatted input stream
     * @return standardized PDB-formatted input stream
     * @throws IOException
     */
    private static InputStream standardizePdbInputStream(InputStream inputStream) throws IOException {
        BufferedReader br = new BufferedReader(new InputStreamReader(inputStream));

        StringBuilder sb = new StringBuilder();
        Map<String, List<String>> sequences = new LinkedHashMap<>();
        List<String> sequence = null;
        String groupNumber = "";
        char chainId = '.';
        boolean seqres = false;

        String line;
        while ((line = br.readLine()) != null) {
            // check if file contains a SEQRES record.
            if (line.startsWith("SEQRES")) {
                seqres = true;
            }
            if (line.startsWith("ATOM")) {
                // fix Rosetta wrapped atoms names if present
                line = fixRosettaPdb(line);
  
                // collect sequence info if not present in file
                if (!seqres) {
                    // start of a new chain
                    if (line.charAt(21) != chainId) {
                        chainId = line.charAt(21);
                        sequence = new ArrayList<>();
                        sequences.put(Character.toString(chainId), sequence);
                        groupNumber = "";
                    }

                    // start of a new group
                    if (!line.substring(22, 26).equals(groupNumber)) {
                        sequence.add(line.substring(17, 20));
                        groupNumber = line.substring(22, 26);
                    }
                }
            }
            sb.append(line + "\n");
        }

        br.close();

        // prepend SEQRES records if not present
        if (!seqres) {
            sb = createSeqresRecords(sequences).append(sb);
        }

        return new ByteArrayInputStream(sb.toString().getBytes());
    }

    /**
     * Creates a standard PDB SEQRES record from a list of residue names.
     * <p>
     * Format: SEQRES 1 A 159 ASP PRO SER LYS ASP SER LYS ALA GLN VAL SER ALA
     * ALA
     * 
     * @param sequence
     *            list of 3-character residue names
     * @return SEQRES record
     */
    private static StringBuilder createSeqresRecords(Map<String, List<String>> sequences) {
        StringBuilder builder = new StringBuilder();
        
        for (Entry<String, List<String>> sequenceRecord : sequences.entrySet()) {
            String chainId = sequenceRecord.getKey();
            List<String> sequence = sequenceRecord.getValue();
            int nRecords = (sequence.size() + 12) / 13;
            for (int i = 0; i < nRecords; i++) {
                builder.append("SEQRES");
                builder.append(String.format("%4d", i));
                builder.append(" ");
                builder.append(chainId);
                builder.append(String.format("%5d", sequence.size()));
                int start = i * 13;
                int end = Math.min((i + 1) * 13, sequence.size());
                for (int j = start; j < end; j++) {
                    builder.append(" ");
                    builder.append(sequence.get(j));
                }
                builder.append("\n");
            }
        }
        return builder;
    }

    /**
     * Moves "wrapped" digit in atom names from the first to the last position,
     * e.g., ATOM 47 2HD2 ASN -> ATOM 47 2HD2 ASN
     * 
     * @param line PDB ATOM or HETATOM record
     * @return fixed PDB ATOM or HETATOM record
     */
    private static String fixRosettaPdb(String line) {
        // wrapped atom names have a digit at position 12
        char line12 = line.charAt(12);
        if (Character.isDigit(line12)) {
            StringBuilder sb = new StringBuilder(line);

            if (line.charAt(14) == ' ') {
                // case 1: "ATOM 8 1H VAL..." -> "ATOM 8 H1 VAL..."
                sb.setCharAt(12, ' ');
                sb.setCharAt(14, line12);
            } else if (line.charAt(15) == ' ') {
                // case 2: "ATOM 30 1HB GLU..." -> "ATOM 30 HB1 GLU..."
                sb.setCharAt(12, ' ');
                sb.setCharAt(15, line12);
            } else if (line.charAt(15) != ' ') {
                // case 3: "ATOM 46 1HD2 ASN..." -> "ATOM 46 HD21 ASN..."
                sb.deleteCharAt(12);
                sb.insert(15, line12);
            }

            line = sb.toString();
        }
        return line;
    }


    /**
     * Get list of files from the path
     * 
     * @param path
     *            File path
     * @return list of files in the path
     */
    private static List<File> getFiles(String path) {
        List<File> fileList = new ArrayList<File>();
        for (File f : new File(path).listFiles()) {
            if (f.isDirectory()) {
                fileList.addAll(getFiles(f.toString()));
            } else {
                String filePath = f.getName();
                if (filePath.endsWith(".gz")) {
                    filePath = filePath.substring(0, filePath.length() - 4);
                }
                if (filePath.endsWith(".pdb") || filePath.endsWith(".ent") || filePath.endsWith(".cif")) {
                    fileList.add(f);
                }
            }
        }
        return fileList;
    }
}
