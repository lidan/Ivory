/*
 * Ivory: A Hadoop toolkit for web-scale information retrieval
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

package ivory.smrf.model.score;

import ivory.smrf.model.GlobalEvidence;
import ivory.smrf.model.GlobalTermEvidence;
import ivory.util.XMLTools;

import org.w3c.dom.Node;

/**
 * Computes score based on BM25.
 *
 * @author Don Metzler
 *
 */
public class BM25ScoringFunction extends ScoringFunction {
	public float k1 = 1.2f;
	public float b = 0.75f;
	public float avgDocLen;
	public String idfType = "okapi";
	public float idf;
	public float maxScore;

	@Override
	public void configure(Node domNode) {
		k1 = XMLTools.getAttributeValue(domNode, "k1", 1.2f);
		b = XMLTools.getAttributeValue(domNode, "b", 0.75f);
		idfType = XMLTools.getAttributeValue(domNode, "idf", "okapi");
	}

	@Override
	public float getScore(int tf, int docLen) {
		return ((k1 + 1.0f) * tf) / (k1 * ((1.0f - b) + b * docLen / avgDocLen) + tf) * idf;
	}

	@Override
	public String toString() {
		return "<scoringfunction>BM25</scoringfunction>\n";
	}

	@Override
	public void initialize(GlobalTermEvidence termEvidence, GlobalEvidence globalEvidence) {
		avgDocLen = (float) globalEvidence.collectionLength / (float) globalEvidence.numDocs;

		if ("none".equals(idfType)) {
			idf = 1;
		} else if ("classic".equals(idfType)) {
			idf = (float) Math.log((float) globalEvidence.numDocs / (float) termEvidence.getDf());
		} else if ("okapi-positive".equals(idfType)) {
			idf = (float) Math.log(((float) globalEvidence.numDocs + 0.5f) / ((float) termEvidence.getDf() + 0.5f));
		} else {
			// Defaults to "Okapi" idf.
			idf = (float) Math.log(((float) globalEvidence.numDocs - (float) termEvidence.getDf() + 0.5f) / ((float) termEvidence.getDf() + 0.5f));
		}
		
		maxScore = (k1 + 1.0f) * idf;
	}

	@Override
	public float getMinScore() {
		// TODO: make this bound tighter
		return Float.NEGATIVE_INFINITY;
	}
	
	@Override
	public float getMaxScore() {
		return maxScore;
	}

	public float getAvgDocLen(){
		return avgDocLen;
	}

	public float getK1(){
		return k1;
	}
	
	public float getB(){
		return b;
	}

}
