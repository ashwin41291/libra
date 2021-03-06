/*
 * Copyright 2016 iychoi.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package libra.core.kmersimilarity_m;

import java.io.IOException;
import libra.common.helpers.FileSystemHelper;
import libra.common.report.Report;
import libra.common.cmdargs.CommandArgumentsParser;
import libra.common.helpers.MapReduceClusterHelper;
import libra.common.helpers.MapReduceHelper;
import libra.common.kmermatch.KmerMatchFileMapping;
import libra.common.kmermatch.KmerMatchInputFormat;
import libra.common.kmermatch.KmerMatchInputFormatConfig;
import libra.core.CoreCmdArgs;
import libra.core.commom.CoreConfig;
import libra.core.commom.CoreConfigException;
import libra.core.common.helpers.KmerSimilarityHelper;
import libra.core.common.kmersimilarity.KmerSimilarityOutputRecord;
import libra.preprocess.common.helpers.KmerIndexHelper;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.LineRecordReader;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;

/**
 *
 * @author iychoi
 */
@SuppressWarnings("deprecation")
public class KmerSimilarityMap extends Configured implements Tool {
    
    private static final Log LOG = LogFactory.getLog(KmerSimilarityMap.class);
    
    private static final int PARTITIONS_PER_CORE = 10;
    
    public static void main(String[] args) throws Exception {
        int res = ToolRunner.run(new Configuration(), new KmerSimilarityMap(), args);
        System.exit(res);
    }
    
    public static int main2(String[] args) throws Exception {
        return ToolRunner.run(new Configuration(), new KmerSimilarityMap(), args);
    }
    
    public KmerSimilarityMap() {
        
    }
    
    @Override
    public int run(String[] args) throws Exception {
        CommandArgumentsParser<CoreCmdArgs> parser = new CommandArgumentsParser<CoreCmdArgs>();
        CoreCmdArgs cmdParams = new CoreCmdArgs();
        if(!parser.parse(args, cmdParams)) {
            LOG.error("Failed to parse command line arguments!");
            return 1;
        }
        
        CoreConfig cConfig = cmdParams.getCoreConfig();
        
        return runJob(cConfig);
    }
    
    private void validateLibraConfig(CoreConfig cConfig) throws CoreConfigException {
        if(cConfig.getKmerIndexPath() == null) {
            throw new CoreConfigException("cannot find input kmer index path");
        }
        
        if(cConfig.getKmerHistogramPath() == null) {
            throw new CoreConfigException("cannot find kmer histogram path");
        }
        
        if(cConfig.getKmerStatisticsPath() == null) {
            throw new CoreConfigException("cannot find kmer statistics path");
        }
        
        if(cConfig.getOutputPath() == null) {
            throw new CoreConfigException("cannot find output path");
        }
    }
    
    private int runJob(CoreConfig cConfig) throws Exception {
        // check config
        validateLibraConfig(cConfig);
        
        // configuration
        Configuration conf = this.getConf();
        
        Job job = new Job(conf, "Libra Core - Computing similarity between samples");
        conf = job.getConfiguration();
        
        // set user configuration
        cConfig.saveTo(conf);
        
        job.setJarByClass(KmerSimilarityMap.class);
        
        // Mapper
        job.setMapperClass(KmerSimilarityMapper.class);
        job.setInputFormatClass(KmerMatchInputFormat.class);
        job.setMapOutputKeyClass(Text.class);
        job.setMapOutputValueClass(Text.class);
        
        // Specify key / value
        job.setOutputKeyClass(Text.class);
        job.setOutputValueClass(Text.class);

        // Inputs
        Path[] kmerIndexFiles = KmerIndexHelper.getAllKmerIndexIndexFilePath(conf, cConfig.getKmerIndexPath());
        KmerMatchInputFormat.addInputPaths(job, FileSystemHelper.makeCommaSeparated(kmerIndexFiles));

        LOG.info("Input kmer index files : " + kmerIndexFiles.length);
        for(Path inputFile : kmerIndexFiles) {
            LOG.info("> " + inputFile.toString());
        }
        
        int kmerSize = 0;
        for(Path inputFile : kmerIndexFiles) {
            // check kmerSize
            int myKmerSize = KmerIndexHelper.getKmerSize(inputFile);
            if(kmerSize == 0) {
                kmerSize = myKmerSize;
            } else {
                if(kmerSize != myKmerSize) {
                    throw new Exception("kmer size must be the same over all given kmer indices");
                }
            }
        }
        
        KmerMatchFileMapping fileMapping = new KmerMatchFileMapping();
        for(Path kmerIndexFile : kmerIndexFiles) {
            String fastaFilename = KmerIndexHelper.getFastaFileName(kmerIndexFile.getName());
            fileMapping.addFastaFile(fastaFilename);
        }
        fileMapping.saveTo(conf);
        
        int MRNodes = MapReduceClusterHelper.getNodeNum(conf);
        
        LOG.info("MapReduce nodes detected : " + MRNodes);

        KmerMatchInputFormatConfig matchInputFormatConfig = new KmerMatchInputFormatConfig();
        matchInputFormatConfig.setKmerSize(kmerSize);
        matchInputFormatConfig.setPartitionNum(MRNodes * PARTITIONS_PER_CORE);
        matchInputFormatConfig.setKmerHistogramPath(cConfig.getKmerHistogramPath());
        
        KmerMatchInputFormat.setInputFormatConfig(job, matchInputFormatConfig);
        
        FileOutputFormat.setOutputPath(job, new Path(cConfig.getOutputPath()));
        job.setOutputFormatClass(TextOutputFormat.class);

        // Reducer
        job.setNumReduceTasks(0);
        
        // Execute job and return status
        boolean result = job.waitForCompletion(true);

        // commit results
        if(result) {
            commit(new Path(cConfig.getOutputPath()), conf);
            
            Path tableFilePath = new Path(cConfig.getOutputPath(), KmerSimilarityHelper.makeKmerSimilarityTableFileName());
            FileSystem fs = tableFilePath.getFileSystem(conf);
            fileMapping.saveTo(fs, tableFilePath);
            
            // combine results
            sumScores(new Path(cConfig.getOutputPath()), conf);
        }
        
        // report
        if(cConfig.getReportPath() != null && !cConfig.getReportPath().isEmpty()) {
            Report report = new Report();
            report.addJob(job);
            report.writeTo(cConfig.getReportPath());
        }
        
        return result ? 0 : 1;
    }
    
    private void commit(Path outputPath, Configuration conf) throws IOException {
        FileSystem fs = outputPath.getFileSystem(conf);
        
        FileStatus status = fs.getFileStatus(outputPath);
        if (status.isDir()) {
            FileStatus[] entries = fs.listStatus(outputPath);
            for (FileStatus entry : entries) {
                Path entryPath = entry.getPath();
                
                // remove unnecessary outputs
                if(MapReduceHelper.isLogFiles(entryPath)) {
                    fs.delete(entryPath, true);
                } else if(MapReduceHelper.isPartialOutputFiles(entryPath)) {
                    // rename outputs
                    int mapreduceID = MapReduceHelper.getMapReduceID(entryPath);
                    String newName = KmerSimilarityHelper.makeKmerSimilarityResultFileName(mapreduceID);
                    Path toPath = new Path(entryPath.getParent(), newName);

                    LOG.info("output : " + entryPath.toString());
                    LOG.info("renamed to : " + toPath.toString());
                    fs.rename(entryPath, toPath);
                } else {
                    // let it be
                }
            }
        } else {
            throw new IOException("path not found : " + outputPath.toString());
        }
    }
    
    private void sumScores(Path outputPath, Configuration conf) throws IOException {
        Path[] resultFiles = KmerSimilarityHelper.getAllKmerSimilarityResultFilePath(conf, outputPath.toString());
        FileSystem fs = outputPath.getFileSystem(conf);

        KmerSimilarityOutputRecord scoreRec = null;
        for(Path resultFile : resultFiles) {
            LOG.info("Reading the scores from " + resultFile.toString());
            FSDataInputStream is = fs.open(resultFile);
            FileStatus status = fs.getFileStatus(resultFile);
            
            LineRecordReader reader = new LineRecordReader(is, 0, status.getLen(), conf);
            
            LongWritable off = new LongWritable();
            Text val = new Text();

            while(reader.next(off, val)) {
                if(scoreRec == null) {
                    scoreRec = KmerSimilarityOutputRecord.createInstance(val.toString());
                } else {
                    KmerSimilarityOutputRecord rec2 = KmerSimilarityOutputRecord.createInstance(val.toString());
                    scoreRec.addScore(rec2.getScore());
                }
            }
            
            reader.close();
        }
        
        double[] accumulatedScore = scoreRec.getScore();
        
        String resultFilename = KmerSimilarityHelper.makeKmerSimilarityFinalResultFileName();
        Path resultFilePath = new Path(outputPath, resultFilename);
        
        LOG.info("Creating a final score file : " + resultFilePath.toString());
        
        FSDataOutputStream os = fs.create(resultFilePath);
        int n = (int)Math.sqrt(accumulatedScore.length);
        for(int i=0;i<accumulatedScore.length;i++) {
            int x = i/n;
            int y = i%n;
            
            String k = x + "-" + y;
            String v = Double.toString(accumulatedScore[i]);
            if(x == y) {
                v = Double.toString(1.0);
            }
            String out = k + "\t" + v + "\n";
            os.write(out.getBytes());
        }
        
        os.close();
    }
}
