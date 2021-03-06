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

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import org.apache.hadoop.io.Writable;

/**
 * Object representing a posting. A posting contains two elements: a docno and a
 * score. In most cases, the score is the term frequency of a term within the
 * document, but in the case of impact-based indexes, the score is the impact
 * score.
 * 
 * @author Jimmy Lin
 * 
 */
public class Posting implements Writable {

	private int mDocno;
	private short mScore;

	/**
	 * Creates a new empty posting.
	 */
	public Posting() {
		super();
	}

	/**
	 * Creates a new posting with a specific docno and score.
	 */
	public Posting(int docno, short score) {
		super();
		this.mDocno = docno;
		this.mScore = score;
	}

	/**
	 * Returns the docno of this posting.
	 */
	public int getDocno() {
		return mDocno;
	}

	/**
	 * Sets the docno of this posting.
	 */
	public void setDocno(int docno) {
		mDocno = docno;
	}

	/**
	 * Returns the score of this posting. Most typically, this is the term
	 * frequency, but can also be the impact score in the case of impact-based
	 * indexes.
	 */
	public short getScore() {
		return mScore;
	}

	/**
	 * Sets the score of this posting. Most typically, this is the term
	 * frequency, but can also be the impact score in the case of impact-based
	 * indexes.
	 */
	public void setScore(short tf) {
		mScore = tf;
	}

	/**
	 * Deserializes this posting.
	 */
	public void readFields(DataInput in) throws IOException {
		mDocno = in.readInt();
		mScore = in.readShort();
	}

	/**
	 * Serializes this posting.
	 */
	public void write(DataOutput out) throws IOException {
		out.writeInt(mDocno);
		out.writeShort(mScore);
	}

	/**
	 * Clones this posting.
	 */
	public Posting clone() {
		return new Posting(this.getDocno(), this.getScore());
	}

	/**
	 * Generates a human-readable String representation of this object.
	 */
	public String toString() {
		return "(" + mDocno + ", " + mScore + ")";
	}
}
