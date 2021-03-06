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

package ivory.smrf.retrieval;

import ivory.exception.ConfigurationException;
import ivory.smrf.model.Clique;
import ivory.smrf.model.Clique_cascade;
import ivory.smrf.model.DocumentNode;
import ivory.smrf.model.GraphNode;
import ivory.smrf.model.MarkovRandomField;
import ivory.util.RetrievalEnvironment;
import ivory.smrf.model.score.DirichletScoringFunction_cascade;
import ivory.smrf.model.score.BM25ScoringFunction_cascade;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.HashMap;
import java.util.BitSet;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;

/**
 * @author Lidan Wang
 * 
 */
public class CascadeEval{

	private static final Logger sLogger = Logger.getLogger(CascadeEval.class);

	static {
		sLogger.setLevel(Level.WARN);
	}

	/**
	 * Pool of accumulators.
	 */
	private Accumulator_cascade[] mAccumulators = null;

	/**
	 * Sorted list of accumulators.
	 */
	private final PriorityQueue<Accumulator_cascade> mSortedAccumulators = new PriorityQueue<Accumulator_cascade>();

	/**
	 * Comparator used to sort cliques by their max score.
	 */
	private final Comparator<Clique> mMaxScoreComparator = new Clique.MaxScoreComparator();

	/**
	 * Markov Random Field that we are using to generate the ranking.
	 */
	private MarkovRandomField mMRF = null;

	/**
	 * If defined, only documents within this set will be scored.
	 */
	private int[] mDocSet = null;
	float [] accumulated_scores = null;

	//Declare these so don't have to repeatedly declaring them in the methods
	double [] mDocSet_tmp;
	float [] accumulated_scores_tmp;
	int [] order;


	/**
	 * MRF document nodes.
	 */
	private List<DocumentNode> mDocNodes = null;

	/**
	 * Maximum number of results to return.
	 */
	private int mNumResults;

	//saved results from internalInputFile
	private float [][] mSavedResults ;

	//K value used in cascade model
	private int mK;

	//Lidan: query ID 
	private String mQid;

	Accumulator_cascade[] results = null;
	Accumulator_cascade[] results_tmp = null;

	List<Clique> cliques_all;
	int cnt;

	int cascadeStage;

	//Cost of this cascade model = # documents * sum of unit per document cost over the cliques
	float cascadeCost = 0;


	//Lidan: # output documents from the initial stage
	static int numOutputs_firstStage = 20000;

	//docs that will be passed around
	int [][][] keptDocs;
	int [] keptDocLengths;

	//single terms in cliques used in first stage, which clique number they correspond to, keyed by the concept, value is the cliqueNumber or termCollectionFrequency
	HashMap term_to_cliqueNumber = new HashMap();
	HashMap term_to_termCollectionFrequency = new HashMap();
	HashMap term_to_termDF = new HashMap();

	//for pruning use
	float meanScore = 0;
	float stddev = 0;

	int numQueryTerms;

	public static int defaultNumDocs = 9999999;

	public CascadeEval(MarkovRandomField mrf, int numResults, String qid, float [][] savedResults, int K) {
		this(mrf, null, numResults, qid, savedResults, K);
	}

	public CascadeEval(MarkovRandomField mrf, int[] docSet, int numResults, String qid, float [][] savedResults, int K) {
		mMRF = mrf;
		mDocSet = docSet;
		mNumResults = numResults;
		mDocNodes = getDocNodes();

		mQid = qid;

		mSavedResults = savedResults;

		mK = K;

		//Lidan: get # query terms
		numQueryTerms = mMRF.getQueryTerms().length;

		keptDocs = new int[numOutputs_firstStage + 1][numQueryTerms][];

		keptDocLengths = new int[numOutputs_firstStage + 1];

		/*
		// Create single pool of reusable accumulators.
		mAccumulators = new Accumulator_cascade[numResults + 1];
		for (int i = 0; i < numResults + 1; i++) {
			mAccumulators[i] = new Accumulator_cascade(0, 0.0f);
		}
		*/
	}

	
	//Lidan: assuming mDocSet[] & accumulated_scores[] sorted by descending order of scores!
	//Lidan: this method modifies mDocSet[] & accumulated_scores[] (class variables)
	public void pruneDocuments (String pruner, float pruner_param){

		//After pruning, make sure have max(RetrievalEnvironment.mCascade_K, |retained docs|) documents!
	
		int [] mDocSet_tmp = new int[mDocSet.length];
		float [] accumulated_scores_tmp = new float[accumulated_scores.length];

		int retainSize = 0; 

		if (pruner.equals("score")){

			float max_score = accumulated_scores[0];
			float min_score = accumulated_scores[accumulated_scores.length-1];

			//float score_threshold = max_score * pruner_param;
			float score_threshold = (max_score - min_score) * pruner_param + min_score;
			
			for (int i=0; i<accumulated_scores.length; i++){
				if (score_threshold <= accumulated_scores[i]){
					retainSize++;
				}
				else{
					break;
				}
			}

			//if (retainSize < RetrievalEnvironment.mCascade_K){
			//	retainSize = RetrievalEnvironment.mCascade_K;
			//}

		}
		else if (pruner.equals("mean-max")){
			float max_score = accumulated_scores[0];
			float mean_score = 0;
			for (int j=0; j<accumulated_scores.length; j++){
				mean_score += accumulated_scores[j];
			}
			mean_score = mean_score/(float)accumulated_scores.length;
			float score_threshold = pruner_param * max_score + (1.0f - pruner_param) * mean_score;

			for (int i=0; i<accumulated_scores.length; i++){
				if (score_threshold <= accumulated_scores[i]){
					retainSize++;
				}
				else{
					break;
				}
			}

		}
		else if (pruner.equals("rank")){
			//if pruner_param = 0.3 --> remove bottom 30% of the docs!

			retainSize = (int)((1.0-pruner_param)*((double)(mDocSet.length)));

			//retainSize = (int)(Math.max(RetrievalEnvironment.mCascade_K, (1.0-pruner_param)*((double)(mDocSet.length)) ));
		}
		else if (pruner.equals("z-score")){

			//compute mean
			float avgScores = 0.0f;

			for (int i=0; i<accumulated_scores.length; i++){
				avgScores+=accumulated_scores[i];
			}
			avgScores = avgScores / (float) accumulated_scores.length;

			//compute variance		
			float variance = 0.0f;
			for (int i=0; i<accumulated_scores.length; i++){
				variance += (accumulated_scores[i] - avgScores) * (accumulated_scores[i] - avgScores);
			}			
			float stddev = (float) Math.sqrt(variance);

			float [] z_scores = new float[accumulated_scores.length];
			for (int i=0; i<z_scores.length; i++){
				z_scores[i] = (accumulated_scores[i] - avgScores)/stddev;
			}	
		}
                else{
                        System.out.println("Pruner "+pruner+" is not supported!");
                        System.exit(-1);
                }


		if (retainSize < mK){ //RetrievalEnvironment.mCascade_K){
			if (mDocSet.length >= mK){ 
				retainSize = mK; 
			}
			//When training the model, set the # output docs large on puropse so that output size = retained docs size 
			//else if (RetrievalEnvironment.mCascade_K!=RunTraining.defaultNumDocs){ 

			else if (mK!=defaultNumDocs){ 
				retainSize = mDocSet.length;
			}
		}

		if (retainSize > mDocSet.length){
			retainSize = mDocSet.length;
		}

		for (int i=0; i<retainSize; i++){ 
			mDocSet_tmp[i] = mDocSet[i];
			accumulated_scores_tmp[i] = accumulated_scores[i];
		}
		mDocSet = new int[retainSize];
		accumulated_scores = new float[retainSize];

		for (int i=0; i<retainSize; i++){ 
			mDocSet[i] = mDocSet_tmp[i];
			accumulated_scores[i] = accumulated_scores_tmp[i];
		}

	}


	//Lidan: operate on class vars mDocSet[] & accumulated_scores
	public void sortDocumentsByDocnos(){
		order = new int[mDocSet.length];
		mDocSet_tmp =  new double[mDocSet.length];
		accumulated_scores_tmp = new float[mDocSet.length];

		for (int i=0; i<order.length; i++){
			order[i] = i;
			mDocSet_tmp[i] = mDocSet[i];
			accumulated_scores_tmp[i] = accumulated_scores[i];
		}

		ivory.smrf.model.constrained.ConstraintModel.Quicksort(mDocSet_tmp, order, 0, order.length-1);

		for (int i=0; i<order.length; i++){
			mDocSet[i] = (int) mDocSet_tmp[i];
			accumulated_scores[i] = accumulated_scores_tmp[order[i]];
		}

	}

	//Total cost of the cascade model: # documents * sum of unit per document cost over each clique
	public float getCascadeCost(){

		//Lidan: should cast it to [0, 1]

		float normalizedCost = 1.0f - (float)(Math.exp(-0.01 * cascadeCost/50000)); 
		return normalizedCost;
	}

	public Accumulator[] rank(){

		if (mSavedResults != null){
			mDocSet = new int[mSavedResults.length];
			accumulated_scores = new float[mSavedResults.length];

			for (int i=0; i<mSavedResults.length; i++){
				mDocSet[i] = (int)mSavedResults[i][0];
				accumulated_scores[i] = mSavedResults[i][1];
			}

			keptDocs = new int[mDocSet.length + 1][numQueryTerms][];
			keptDocLengths = new int[mDocSet.length + 1];
		}	
	
		// Initialize the MRF ==> this will clear out postings readers cache!
                try {
                        mMRF.initialize();
                } catch (ConfigurationException e) {
                        sLogger.error("Error initializing MRF. Aborting ranking!");
                        return null;
                }

		// Cliques associated with the MRF.
		cliques_all = new ArrayList();
		List<Clique> cliques = mMRF.getCliques();
		for (int i=0; i<cliques.size(); i++){
			cliques_all.add(cliques.get(i));
		}

		//Cascade stage starts at 0                
		cascadeStage = 0;

		cnt=0;

		String pruner = null;
		float pruner_param = -1;


		long startTime = System.currentTimeMillis();

		int termMatches = 0;
		
		while (cnt!=cliques_all.size()){ //if not have gone thru all cascade stages

			float subTotal_cascadeCost = 0;

			long endTime = System.currentTimeMillis();
				
			startTime = System.currentTimeMillis();

			if (cascadeStage < 1){ //only call once, then use keptDocs[][][]
				mMRF.removeAllCliques();
			
				for (Clique c: cliques_all){
					int cs = ((Clique_cascade)c).getCascadeStage();
					if (cascadeStage == cs){

						//c.resetPostingsListReader();
						mMRF.addClique(c);
						cnt++;
						//mNumResults = c.getNumResults();
						pruner = ((Clique_cascade)c).getPruner();
						pruner_param = ((Clique_cascade)c).getPruner_param();

						if (cascadeStage == 0){

							int numDocs = Integer.MAX_VALUE;
		
							if (mDocSet == null){
								try{ //c.getNumberOfPostings() is not supported for bigram postings readers

									numDocs = ((Clique_cascade)c).getNumberOfPostings();
								}
								catch(Exception e){}

								//(not) ignore cost of first stage from the cost model
								subTotal_cascadeCost += ((Clique_cascade)c).mUnitCost * numDocs;
							}
							else{
								subTotal_cascadeCost += ((Clique_cascade)c).mUnitCost;
							}
						}
						else{
							subTotal_cascadeCost += ((Clique_cascade)c).mUnitCost;
						}
					}
				}

				if (mDocSet != null) {
		
					//Lidan: mDocSet[] & accumulated_scores[] should be sorted by doc scores!
					//Lidan: this method opereates on mDocSet[] & accumulated_scores[]!
					pruneDocuments (pruner, pruner_param);

					//Lidan: will score all documents in the retained documenet set
					mNumResults = mDocSet.length;

					sortDocumentsByDocnos();
                         
					//Cost = cost of applying the feature on the retained documents after pruning
					subTotal_cascadeCost = subTotal_cascadeCost * mNumResults;

				}
				else{				
					//Lidan: first cascade stage, just output 20000 documents

					mNumResults = numOutputs_firstStage;// 20000;

					if (cascadeStage != 0){
						System.out.println("Should be the first stage here!");
						System.exit(-1);
					}
				}

				// Create single pool of reusable accumulators.
                        	mAccumulators = new Accumulator_cascade[mNumResults + 1];
                        	for (int i = 0; i < mNumResults + 1; i++) {
                             		mAccumulators[i] = new Accumulator_cascade(0, 0.0f);
                        	}

				results = rank_cascade();

				cascadeStage++;
			}
			else{	 			
				String featureID = null;
				String scoringFunction = null;
				int mSize = -1;
				String [][] concepts_this_stage = new String[cliques_all.size()][];
				float [] clique_wgts = new float[concepts_this_stage.length];

				int cntConcepts = 0;

				for (Clique c: cliques_all){
					int cs = ((Clique_cascade)c).getCascadeStage();
					if (cascadeStage == cs){
						cnt++;
						pruner = ((Clique_cascade)c).getPruner();
						pruner_param = ((Clique_cascade)c).getPruner_param();

						featureID = ((Clique_cascade)c).getParamID().trim(); //termWt, orderedWt, unorderedWt							
						scoringFunction = ((Clique_cascade)c).getScoringFunctionName();	 //dirichlet, bm25

						mSize = ((Clique_cascade)c).getWindowSize(); //window width
						if (mSize == -1 && !(featureID.equals("termWt"))){
							System.out.println("Only term features don't support getWindowSize()! "+featureID);
							System.exit(-1);
						}				
						concepts_this_stage[cntConcepts] = ((Clique_cascade)c).getSingleTerms();
						clique_wgts[cntConcepts] = c.getWeight();

						cntConcepts++;
						subTotal_cascadeCost += ((Clique_cascade)c).mUnitCost;
					}
				}

				//for use in pruning

				//score-based
				float max_score = results[0].score;
				float min_score = results[results.length-1].score;
				float score_threshold = (max_score - min_score)* pruner_param + min_score;
				
				float mean_max_score_threshold = pruner_param * max_score + (1.0f - pruner_param) * meanScore;

				//rank-based
				int retainSize = (int)((1.0-pruner_param)*((double)(results.length)));


				int keepVal = results.length;


				int size = 0;

				// Clear priority queue.
				mSortedAccumulators.clear();

				float [] termCollectionFreqs = new float[cntConcepts];
				float [] termDFs = new float[cntConcepts];
				int [][] termIndexes = new int[cntConcepts][];

				float sumScore = 0;

				for (int j=0; j<cntConcepts; j++){
                                                        
					//String c = concepts_this_stage[j];
                                                        
					String [] singleTerms = concepts_this_stage[j]; //c.split("\\s+");

					int termIndex1 = Integer.parseInt((String)(term_to_cliqueNumber.get(singleTerms[0])));

					if (featureID.indexOf("termWt")!=-1){
						float termCollectionFreq = Float.parseFloat((String)(term_to_termCollectionFrequency.get(singleTerms[0])));
						termCollectionFreqs[j] = termCollectionFreq;

						float termDF = Float.parseFloat((String)(term_to_termDF.get(singleTerms[0])));
						termDFs[j] = termDF;

						termIndexes[j] = new int[1];
						termIndexes[j][0] = termIndex1;

						if (singleTerms.length!=1){
							System.out.println("Should have length 1 "+singleTerms.length);
							System.exit(-1);
						}
					}
					else{
						int termIndex2 = Integer.parseInt((String)(term_to_cliqueNumber.get(singleTerms[1])));

						termIndexes[j] = new int[2];
						termIndexes[j][0] = termIndex1;
						termIndexes[j][1] = termIndex2;


						if (singleTerms.length!=2){
							System.out.println("Should have length 2 "+singleTerms.length);
							System.exit(-1);
						}
					}

				}


				startTime = System.currentTimeMillis();

				//iterate over results documents, which are sorted in scores
				for (int i=0; i<results.length; i++){
					//pruning, if okay, scoring, update pruning stats for next cascade stage

					boolean passedPruning = false;
					if (pruner.equals("rank")){
						if (i<retainSize){
							passedPruning = true;
						}	
						else{
							if (size < mK && mK!=defaultNumDocs){
								passedPruning = true;
							}
							else{
								break;
							}
						}
					}
					else if (pruner.equals("score")){
						if (results[i].score > score_threshold){
							passedPruning = true;
						}
						else{
							if (size < mK && mK!=defaultNumDocs){
								passedPruning = true;
							}
							else{
								break;
							}
						}
					}
					else if (pruner.equals("mean-max")){
						if (results[i].score > mean_max_score_threshold){
							passedPruning = true;
						}
						else{
							if (size < mK && mK!=defaultNumDocs){
								passedPruning = true;
							}
							else{
								break;
							}
						}
					}
					else if (pruner.equals("z-score")){				
						float z_score = (results[i].score - meanScore)/stddev;
					}
					
					else{
						//System.out.println("Not supported pruner! "+pruner);
					}

					if (passedPruning){

						size++;
			
						int docIndex = results[i].index_into_keptDocs;

						int docLen = keptDocLengths[docIndex];

						float docScore_cascade = 0;

						for (int j=0; j<cntConcepts; j++){
							//String c = concepts_this_stage[j];
							String [] singleTerms = concepts_this_stage[j]; //c.split("\\s+");

							if (featureID.equals("termWt")){

								int termIndex1 = termIndexes[j][0]; //Integer.parseInt((String)(term_to_cliqueNumber.get(singleTerms[0])));
								int [] positions1 = keptDocs[docIndex][termIndex1];

								int tf = 0;

								if (positions1 != null){
									tf = positions1.length;
								}
								float termCollectionFreq = termCollectionFreqs[j]; //Float.parseFloat((String)(term_to_termCollectionFrequency.get(singleTerms[0])));
								float termDF = termDFs[j];

								docScore_cascade += clique_wgts[j] * getScore(tf, docLen, termCollectionFreq, termDF, scoringFunction);

							}
							else{ //term proximity

								//merge into a single stream and compute matches. Assume there are only two terms!!!

								int termIndex1 = termIndexes[j][0]; //Integer.parseInt((String)(term_to_cliqueNumber.get(singleTerms[0])));
								int termIndex2 = termIndexes[j][1]; //Integer.parseInt((String)(term_to_cliqueNumber.get(singleTerms[1])));
		
								int [] positions1 = keptDocs[docIndex][termIndex1];
								int [] positions2 = keptDocs[docIndex][termIndex2];

								int matches = 0;

								if (positions1 != null && positions2 != null){ //both query terms are in the doc

									termMatches++;
									int [] ids = new int[positions1.length];
									Arrays.fill(ids, 0);                
									int length = positions1.length;

									int length2 = positions2.length;

									int [] newPositions = new int[length + length2];
									int [] newIds = new int[length + length2];
	
									int posA = 0;
									int posB = 0;

									int ii=0;
									while(ii < length + length2) {
										if (posB == length2 || posA < length && positions1[posA] <= positions2[posB]){
											newPositions[ii] = positions1[posA];
											newIds[ii] = ids[posA];
											posA++;
										}
										else{
											newPositions[ii] = positions2[posB];
											newIds[ii] = 1;
											posB++;
										}
										ii++;
									}

									int [] positions = newPositions;
									ids = newIds;

									BitSet mMatchedIds = new BitSet(2); //Assume there are only two terms!!!

									if (featureID.equals("orderedWt")){
									
										for( ii = 0; ii < positions.length; ii++ ) {
											mMatchedIds.clear();
											int maxGap = 0;
											boolean ordered = true;
											mMatchedIds.set(ids[ii]);
											int matchedIDCounts = 1;
											int lastMatchedID = ids[ii];
											int lastMatchedPos = positions[ii];
											
											for( int jj = ii+1; jj < positions.length; jj++ ) {
												int curID = ids[jj];
												int curPos = positions[jj];   
												if(!mMatchedIds.get(curID)) {
													mMatchedIds.set(curID);
													matchedIDCounts++;
													if( curID < lastMatchedID ) {
														ordered = false;
													}
													if( curPos - lastMatchedPos > maxGap ) {
														maxGap = curPos - lastMatchedPos;
													}
												}
												// stop looking if the maximum gap is too large
												// or the terms appear out of order
												if(maxGap > mSize || !ordered) {  
													break;
												}
												// did we match all the terms, and in order?
												if(matchedIDCounts == 2 && ordered) {
													matches++;
													break;
												}
											}
										}
									}
									else if (featureID.equals("unorderedWt")){

										for( ii = 0; ii < positions.length; ii++ ) {
											mMatchedIds.clear();

											mMatchedIds.set(ids[ii]);
											int matchedIDCounts = 1;
											int startPos = positions[ii];
										
											for( int jj = ii+1; jj < positions.length; jj++ ) {
												int curID = ids[jj];
												int curPos = positions[jj];   
												int windowSize = curPos - startPos + 1;

												if(!mMatchedIds.get(curID)) {
													mMatchedIds.set(curID);
													matchedIDCounts++;
												}
												// stop looking if we've exceeded the maximum window size
												if(windowSize > mSize ) {
													break;
												}
												// did we match all the terms?
												if(matchedIDCounts == 2) {
													matches++;
													break;
												}
											}
										}
									}
									else{
										System.out.println("Invalid featureID "+featureID);
										System.exit(-1);
									}
								} //end if this is a match, i.e., both query terms are in the doc

								float s = getScore(matches, docLen, RetrievalEnvironment.mDefaultCf, (float)RetrievalEnvironment.mDefaultDf, scoringFunction);
								docScore_cascade += clique_wgts[j] * s;
                                                           					
							}  //end else it's proximity feature
						} //end for (each concept)
		
						//accumulate doc score in results[i] across cascade stages
                                                results[i].score += docScore_cascade;

						mSortedAccumulators.add(results[i]);

						sumScore += results[i].score;

					} //end if passed pruning

				} //end iterating over docs

				endTime = System.currentTimeMillis();

				//order based on new scores in results[], put into priority queue

				if (size!= mSortedAccumulators.size()){
					System.out.println("They should be equal right here "+size+" "+mSortedAccumulators.size());
					System.exit(-1);
				}

				results_tmp = new Accumulator_cascade[size];

				//meanScore = 0;

				meanScore = sumScore / (float)size; //update stats for use in pruning in next cascade stage
				stddev = 0;

				for (int i = 0; i < results_tmp.length; i++) {
					results_tmp[results_tmp.length - 1 - i] = mSortedAccumulators.poll();
					//meanScore += results_tmp[results_tmp.length - 1 - i].score;			//Lidan: before it was like this, when not doing z-score

					stddev += (results_tmp[results_tmp.length - 1 - i].score - meanScore) * (results_tmp[results_tmp.length - 1 - i].score - meanScore);
				}
				results = results_tmp; 

				stddev = (float) Math.sqrt(stddev);

				
				// Create single pool of reusable accumulators.
				//Use mNumResults from prev iteration, since we don't know how many docs are kept until we're done iterating through the documents
				//int retainSize = 0;			
                        	//mAccumulators = new Accumulator[mNumResults + 1];
                        	//for (int i = 0; i < mNumResults + 1; i++) {
				//	mAccumulators[i] = new Accumulator(0, 0.0f);
                        	//}

				
				cascadeStage++;

				subTotal_cascadeCost = subTotal_cascadeCost * size;

			} //end if not first stage
		 
			cascadeCost += subTotal_cascadeCost;

		} //end while

		long endTime = System.currentTimeMillis();

		Accumulator_cascade[] results_return = results;

		if (results.length > mK){ //RetrievalEnvironment.mCascade_K){
 
			results_return = new Accumulator_cascade[mK]; //RetrievalEnvironment.mCascade_K];

			for (int i=0; i<mK; i++){ //RetrievalEnvironment.mCascade_K; i++){
				results_return[i] = new Accumulator_cascade(results[i].docno, results[i].score);
				//results_return[i].docno = results[i].docno;
				//results_return[i].score = results[i].score;
			}
		}

		return results_return;
	}

	public float getScore(int tf, int docLen, float termCollectionFreq, float termDF, String scoringFunction) {
		float score = 0;

		//Lidan: note: here assume we only use one kind of hyperparameter for dirichlet and bm25 in feature set. Since these hyperparameters are declared as static variables in the dirichlet and bm25 scoring functions!

		if (scoringFunction.equals("dirichlet")){
			
			float backgroundProb = termCollectionFreq/DirichletScoringFunction_cascade.collectionLength;

			score = (float) Math.log(((float) tf + DirichletScoringFunction_cascade.MU * backgroundProb) / (docLen + DirichletScoringFunction_cascade.MU));


		}
		else if (scoringFunction.equals("bm25")){
			
			score = ((BM25ScoringFunction_cascade.K1 + 1.0f) * tf) / (BM25ScoringFunction_cascade.K1 * ((1.0f - BM25ScoringFunction_cascade.B) + BM25ScoringFunction_cascade.B * docLen / BM25ScoringFunction_cascade.avg_docLen) + tf);
			float mIdf = (float) Math.log(((float) RetrievalEnvironment.documentCount - termDF + 0.5f) / (termDF + 0.5f));
			score = score * mIdf;
		}
		else{
			System.out.println("Not supported scoringFunction "+scoringFunction);
			System.exit(-1);
		}

		return score;

	}

	public Accumulator_cascade[] rank_cascade() {

		//point to next position in keptDocs array that hasn't been filled
		int indexCntKeptDocs = 0;

		// Clear priority queue.
		mSortedAccumulators.clear();

		// Cliques associated with the MRF.
                List<Clique> cliques = mMRF.getCliques();

		if (cliques.size()==0){
			System.out.println("Shouldn't have size 0");
			System.exit(-1);
		}

		// Current accumulator.
		Accumulator_cascade a = mAccumulators[0];

		/*
		// Initialize the MRF.
		try {
			mMRF.initialize();
		} catch (ConfigurationException e) {
			sLogger.error("Error initializing MRF. Aborting ranking!");
			return null;
		}
		*/

		// Maximum possible score that this MRF can achieve.
		float mrfMaxScore = 0.0f;
		for (Clique c : cliques) {
			if (!((((Clique_cascade)c).getParamID()).equals("termWt"))){
				System.out.println("In this faster cascade implementation, first stage must be term in order to get positions[] values! "+((Clique_cascade)c).getParamID());
				System.exit(-1);
			}
			mrfMaxScore += c.getMaxScore();
		}

		// Sort cliques according to their max scores.
		Collections.sort(cliques, mMaxScoreComparator);

		// Score that must be achieved to enter result set.
		double scoreThreshold = Double.NEGATIVE_INFINITY;

		// Offset into document set we're currently at (if applicable).
		int docsetOffset = 0;
		
		int docno = 0;
		if (mDocSet != null) {
			docno = docsetOffset < mDocSet.length ? mDocSet[docsetOffset++] : Integer.MAX_VALUE;
		} else {
			if (cascadeStage!=0){
				System.out.println("Shouldn't happen. Cascade stage "+cascadeStage);
				System.exit(-1);
			}

			docno = mMRF.getNextCandidate();
		}

		boolean firstTime = true;

		long startTime = System.currentTimeMillis();

		while (docno < Integer.MAX_VALUE) {
			for (DocumentNode documentNode : mDocNodes) {
				documentNode.setDocno(docno);
			}

			// Document-at-a-time scoring.
			float docMaxScore = mrfMaxScore;
			boolean skipped = false;

			float score = 0.0f;

			//Lidan: accumulate document scores across the cascade stages
			if (mDocSet != null && cascadeStage != 0){ 	
				score = accumulated_scores[docsetOffset-1];
			}

			//for each query term, its position in a document
			int [][] termPositions = new int[cliques.size()][];
			int document_length = -1;

			for (int i = 0; i < cliques.size(); i++) {

				// Current clique that we're scoring.
				Clique c = cliques.get(i);

				// If there's no way that this document can enter the result set
				// then exit.

				if (firstTime){
                                        term_to_cliqueNumber.put(c.getConcept().trim().toLowerCase(), i+"");
                                        term_to_termCollectionFrequency.put(c.getConcept().trim().toLowerCase(), ((Clique_cascade)c).termCollectionCF()+"");
					term_to_termDF.put(c.getConcept().trim().toLowerCase(), ((Clique_cascade)c).termCollectionDF()+"");
                                }

				if (score + docMaxScore <= scoreThreshold) {
					// Advance postings readers (but don't score).
					for (int j = i; j < cliques.size(); j++) {
						cliques.get(j).setNextCandidate(docno + 1);
					}
					skipped = true;
			
					break;
				}


				// Document independent cliques do not affect the ranking.
				if (!c.isDocDependent()) {
					continue;
				}

				// Update document score.
				float cliqueScore = c.getPotential();	
				score += c.getWeight() * cliqueScore;

				// Update the max score for the rest of the cliques.
				docMaxScore -= c.getMaxScore();


				//stuff needed for document evaluation in the next stage
				int [] p = ((Clique_cascade)c).getPositions();


				if (p!=null){

					termPositions[i]= Arrays.copyOf(p, p.length);

					document_length = ((Clique_cascade)c).getDocLen();
				}
			}

			firstTime = false;

			// Keep track of mNumResults best accumulators.
			if (!skipped && score > scoreThreshold) {
				a.docno = docno;
				a.score = score;
				a.index_into_keptDocs = indexCntKeptDocs;
				keptDocLengths[indexCntKeptDocs] = document_length;

	 			mSortedAccumulators.add(a);

				//save positional information for each query term in the document
				for (int j=0; j<termPositions.length; j++){

					if (termPositions[j] != null){
						keptDocs[indexCntKeptDocs][j] = Arrays.copyOf(termPositions[j], termPositions[j].length);
					}
				}

				
                                if (mSortedAccumulators.size() == mNumResults + 1) {
                                        a = mSortedAccumulators.poll();                 //Re-use the accumulator of the removed document
                                
                                        //After maximum # docs been put into queue, each time a new document is added, an old document will be ejected, use the spot freed by the ejected document to store the new document positional info in keptDocs
                                        
                                        indexCntKeptDocs = a.index_into_keptDocs;
					keptDocs[indexCntKeptDocs] =  new int[numQueryTerms][];

                                        scoreThreshold = mSortedAccumulators.peek().score;		

                                } else {
                                        a = mAccumulators[mSortedAccumulators.size()]; //Next non-used accumulator in the accumulator pool
                                        indexCntKeptDocs++;
                                }

				
			}

			if (mDocSet != null) {
				docno = docsetOffset < mDocSet.length ? mDocSet[docsetOffset++] : Integer.MAX_VALUE;
			} else {
				if (cascadeStage!=0){
                                	System.out.println("Shouldn't happen. Cascade stage "+cascadeStage);
                                	System.exit(-1);
                        	}

				docno = mMRF.getNextCandidate();
			}
		}

		// Grab the accumulators off the stack, in (reverse) order.
		Accumulator_cascade[] results_tmp = new Accumulator_cascade[Math.min(mNumResults, mSortedAccumulators.size())];

		for (int i = 0; i < results_tmp.length; i++) {
			results_tmp[results_tmp.length - 1 - i] = mSortedAccumulators.poll();
			meanScore += results_tmp[results_tmp.length - 1 - i].score;
		}

		meanScore /= results_tmp.length;

		Accumulator_cascade[] results = results_tmp;

		/* Do the sorting in rank()
		//if there are more stages, should sort by docno
		if (cnt!=cliques_all.size()){
			int [] order = new int[results_tmp.length];
			double [] docnos = new double[results_tmp.length];
			for (int i=0; i<order.length; i++){
				order[i] = i;
				docnos[i] = results_tmp[i].docno;
			}

			ivory.smrf.model.constrained.ConstraintModel.Quicksort(docnos, order, 0, results.length-1);
			results = new Accumulator_cascade[results_tmp.length];
			for (int i=0; i<order.length; i++){
				results[i] = results_tmp[order[i]];
			}
		}
		*/

		long endTime = System.currentTimeMillis();

		return results;
	}


	/**
	 * Returns the Markov Random Field associated with this ranker.
	 */
	public MarkovRandomField getMRF() {
		return mMRF;
	}

	/**
	 * Sets the number of results to return.
	 */
	public void setNumResults(int numResults) {
		mNumResults = numResults;
	}

	private List<DocumentNode> getDocNodes() {
		ArrayList<DocumentNode> docNodes = new ArrayList<DocumentNode>();

		// Check which of the nodes are DocumentNodes.
		List<GraphNode> nodes = mMRF.getNodes();
		for (GraphNode node : nodes) {
			if (node.getType() == GraphNode.Type.DOCUMENT) {
				docNodes.add((DocumentNode) node);
			}
		}
		return docNodes;
	}

}
