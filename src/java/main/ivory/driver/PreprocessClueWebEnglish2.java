/*
 * Ivory: A Hadoop toolkit for Web-scale information retrieval
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You may
 * obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package ivory.driver;

import ivory.preprocess.BuildIntDocVectors2;
import ivory.preprocess.BuildTermDocVectors2;
import ivory.preprocess.BuildTermIdMap2;
import ivory.preprocess.GetTermCount2;
import ivory.util.Constants;
import ivory.util.RetrievalEnvironment;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.apache.log4j.Logger;

public class PreprocessClueWebEnglish2 extends Configured implements Tool {
	private static final Logger sLogger = Logger.getLogger(PreprocessClueWebEnglish.class);

	public static int[] SegmentDocCounts = new int[] { 3382356, 50220423, 51577077, 50547493,
			52311060, 50756858, 50559093, 52472358, 49545346, 50738874, 45175228 };

	public static int[] DocnoOffsets = new int[] { 0, 0, 50220423, 101797500, 152344993, 204656053,
			255412911, 305972004, 358444362, 407989708, 458728582 };

	private static int printUsage() {
		System.out.println("usage: [input-path] [index-path] [segment-num]");
		ToolRunner.printGenericCommandUsage(System.out);
		return -1;
	}

	/**
	 * Runs this tool.
	 */
	public int run(String[] args) throws Exception {
		if (args.length != 3) {
			printUsage();
			return -1;
		}

		String collection = args[0];
		String indexPath = args[1];
		int segment = Integer.parseInt(args[2]);

		sLogger.info("Tool name: BuildTermDocVectorTest2");
		sLogger.info(" - Collection path: " + collection);
		sLogger.info(" - Index path: " + indexPath);
		sLogger.info(" - segement number: " + segment);

		Configuration conf = getConf();
		FileSystem fs = FileSystem.get(conf);
		RetrievalEnvironment env = new RetrievalEnvironment(indexPath, fs);

		Path p = new Path(indexPath);
		if (!fs.exists(p)) {
			sLogger.error("Error: index path doesn't exist!");
			return 0;
		}

		if (!fs.exists(env.getDocnoMappingData())) {
			sLogger.error("Error: docno mapping data doesn't exist!");
			return 0;
		}

		conf.setInt(Constants.NumReduceTasks, 200);

		conf.set(Constants.CollectionName, "ClueWeb:English:Segment" + segment);
		conf.set(Constants.CollectionPath, collection);
		conf.set(Constants.IndexPath, indexPath);
		conf.set(Constants.InputFormat, org.apache.hadoop.mapreduce.lib.input.SequenceFileInputFormat.class.getCanonicalName());
		conf.set(Constants.Tokenizer, ivory.tokenize.GalagoTokenizer.class.getCanonicalName());
		conf.set(Constants.DocnoMappingClass, edu.umd.cloud9.collection.clue.ClueWarcDocnoMapping.class.getCanonicalName());
		conf.set(Constants.DocnoMappingFile, env.getDocnoMappingData().toString());

		conf.setInt(Constants.DocnoOffset, DocnoOffsets[segment]);
		conf.setInt(Constants.MinDf, 10);
		conf.setInt(Constants.MaxDf, Integer.MAX_VALUE);
		conf.setInt(Constants.TermIndexWindow, 8);

		new BuildTermDocVectors2(conf).run();
		new GetTermCount2(conf).run();
		new BuildTermIdMap2(conf).run();
		new BuildIntDocVectors2(conf).run();

		return 0;
	}

	/**
	 * Dispatches command-line arguments to the tool via the
	 * <code>ToolRunner</code>.
	 */
	public static void main(String[] args) throws Exception {
		int res = ToolRunner.run(new PreprocessClueWebEnglish2(), args);
		System.exit(res);
	}
}
