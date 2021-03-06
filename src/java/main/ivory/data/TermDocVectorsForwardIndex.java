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

package ivory.data;

import ivory.preprocess.BuildTermDocVectorsForwardIndex;
import ivory.util.RetrievalEnvironment;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.DecimalFormat;
import java.text.NumberFormat;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.SequenceFile;
import org.apache.log4j.Logger;

import com.google.common.base.Preconditions;

import edu.umd.cloud9.debug.MemoryUsageUtils;

/**
 * Object providing an index into one or more <code>SequenceFile</code>s
 * containing {@link TermDocVector}s, providing random access to the document
 * vectors.
 *
 * @see BuildTermDocVectorsForwardIndex
 *
 * @author Jimmy Lin
 */
public class TermDocVectorsForwardIndex {
	private static final Logger LOG = Logger.getLogger(TermDocVectorsForwardIndex.class);
	private static final NumberFormat FORMAT = new DecimalFormat("00000");

	private final FileSystem fs;
	private final Configuration conf;
	private final long[] positions;
	private final String path;
	private final int docnoOffset;
	private final int collectionDocumentCount;

	/**
	 * Creates a <code>TermDocVectorsIndex</code> object.
	 * 
	 * @param indexPath  location of the index file
	 * @param fs         handle to the FileSystem
	 * @throws IOException
	 */
	public TermDocVectorsForwardIndex(String indexPath, FileSystem fs) throws IOException {
		this.fs = Preconditions.checkNotNull(fs);
		conf = fs.getConf();

		RetrievalEnvironment env = new RetrievalEnvironment(indexPath, fs);
		path = env.getTermDocVectorsDirectory();

		FSDataInputStream posInput = fs.open(new Path(env.getTermDocVectorsForwardIndex()));

		docnoOffset = posInput.readInt();
		collectionDocumentCount = posInput.readInt();

		positions = new long[collectionDocumentCount];
		for (int i = 0; i < collectionDocumentCount; i++) {
			positions[i] = posInput.readLong();
		}
		posInput.close();
	}

	/**
	 * Returns the document vector given a docno.
	 */
	public TermDocVector getDocVector(int docno) throws IOException {
    // TODO: This method re-opens the SequenceFile on every access. Would be more efficient to cache
    // the file handles.
		if (docno > collectionDocumentCount || docno < 1) {
			return null;
		}

		long pos = positions[docno - docnoOffset - 1];

		int fileNo = (int) (pos / BuildTermDocVectorsForwardIndex.BigNumber);
		pos = pos % BuildTermDocVectorsForwardIndex.BigNumber;

		SequenceFile.Reader reader = new SequenceFile.Reader(fs, new Path(path + "/part-"
				+ FORMAT.format(fileNo)), conf);

		IntWritable key = new IntWritable();
		TermDocVector value;

		try {
			value = (TermDocVector) reader.getValueClass().newInstance();
		} catch (Exception e) {
			throw new RuntimeException("Unable to instantiate key/value pair!");
		}

		reader.seek(pos);
		reader.next(key, value);

		if (key.get() != docno) {
			LOG.error("unable to doc vector for docno " + docno + ": found docno " + key
					+ " instead");
			return null;
		}

		reader.close();
		return value;
	}

	/**
	 * Simple test program.
	 */
	public static void main(String[] args) throws Exception {
		if (args.length != 1) {
			System.out.println("usage: [indexPath]");
			System.exit(-1);
		}

		long startingMemoryUse = MemoryUsageUtils.getUsedMemory();

		Configuration conf = new Configuration();

		TermDocVectorsForwardIndex index = new TermDocVectorsForwardIndex(args[0], FileSystem.get(conf));

		long endingMemoryUse = MemoryUsageUtils.getUsedMemory();

		System.out.println("Memory usage: " + (endingMemoryUse - startingMemoryUse) + " bytes\n");

		String term = null;
		BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in));
		System.out.print("Look up postings of docno > ");
		while ((term = stdin.readLine()) != null) {
			int docno = Integer.parseInt(term);
			System.out.println(docno + ": " + index.getDocVector(docno));
			System.out.print("Look up postings of docno > ");
		}
	}
}
