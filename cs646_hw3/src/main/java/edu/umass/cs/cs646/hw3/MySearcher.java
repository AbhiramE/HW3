package edu.umass.cs.cs646.hw3;

import edu.umass.cs.cs646.utils.*;
import org.apache.commons.math3.stat.StatUtils;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.*;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;

import java.io.*;
import java.util.*;

public class MySearcher {
	
	public static void main( String[] args ) {
		try {
			
			String pathIndex = "/home/abhiram/codebase/InformationRetrieval/HW3/index_lucene_robust04";
			Analyzer analyzer = LuceneUtils.getAnalyzer( LuceneUtils.Stemming.Krovetz );
			
			String pathQueries = "/home/abhiram/codebase/InformationRetrieval/HW3/queries";
			String pathQrels = "/home/abhiram/codebase/InformationRetrieval/HW3/qrels";
			String pathStopwords = "/home/abhiram/codebase/InformationRetrieval/HW3/stopwords_inquery";
			
			String field_docno = "docno";
			String field_search = "content";
			
			MySearcher searcher = new MySearcher( pathIndex );
			searcher.setStopwords( pathStopwords );
			
			Map<String, String> queries = EvalUtils.loadQueries( pathQueries );
			Map<String, Set<String>> qrels = EvalUtils.loadQrels( pathQrels );

			int top = 1000;
			double mu = 1000;

			ScoringFunction scoreFunc = new QLDirichletSmoothing( mu );
			
			double[] p10 = new double[queries.size()];
			double[] ap = new double[queries.size()];
			
			double total = 0;
			int ix = 0;
			for ( String qid : queries.keySet() ) {
				
				String query = queries.get( qid );
				List<String> terms = LuceneUtils.tokenize( query, analyzer );
				String[] termsarray = new String[terms.size()];
				for ( int k = 0; k < termsarray.length; k++ ) {
					termsarray[k] = terms.get( k );
				}
				
				List<SearchResult> results = searcher.search( field_search, terms, scoreFunc, top );
				SearchResult.dumpDocno( searcher.index, field_docno, results );
				
				p10[ix] = EvalUtils.precision( results, qrels.get( qid ), 10 );
				ap[ix] = EvalUtils.avgPrec( results, qrels.get( qid ), top );
				
				System.out.printf(
						"%-10s%8.3f%8.3f\n",
						qid,
						p10[ix],
						ap[ix]
				);
				ix++;
			}
			
			System.out.printf(
					"%-10s%-25s%10.3f%10.3f\n",
					"QL",
					"QL",
					StatUtils.mean( p10 ),
					StatUtils.mean( ap )
			);


			searcher.close();

		} catch ( Exception e ) {
			e.printStackTrace();
		}
	}
	
	protected File dirBase;
	protected Directory dirLucene;
	protected IndexReader index;
	protected Map<String, DocLengthReader> doclens;
	
	protected HashSet<String> stopwords;
	
	public MySearcher( String dirPath ) throws IOException {
		this( new File( dirPath ) );
	}
	
	public MySearcher( File dirBase ) throws IOException {
		this.dirBase = dirBase;
		this.dirLucene = FSDirectory.open( this.dirBase.toPath() );
		this.index = DirectoryReader.open( dirLucene );
		this.doclens = new HashMap<>();
		this.stopwords = new HashSet<>();

	}

	public static double logpdc(IndexReader index,String field, String docno)
	{
		double result=0;

		try {
			Terms vector=index.getTermVector(LuceneUtils.findByDocno(index,"docno",docno),field);
			TermsEnum terms = vector.iterator();
			BytesRef term;
			while ( ( term = terms.next() ) != null ) {
				long freq = terms.totalTermFreq();
				String termString = term.utf8ToString();

				double corpusTf=index.totalTermFreq(new Term(field,termString));
				double corpusSize=index.getSumTotalTermFreq(field);
				double probWordInCorpus = Math.log(corpusTf/ corpusSize);

				result += freq * probWordInCorpus;
			}
			return result;
		} catch (IOException e) {
			e.printStackTrace();
		}
		return result;
	}
	
	public void setStopwords( Collection<String> stopwords ) {
		this.stopwords.addAll( stopwords );
	}
	
	public void setStopwords( String stopwordsPath ) throws IOException {
		setStopwords( new File( stopwordsPath ) );
	}
	
	public void setStopwords( File stopwordsFile ) throws IOException {
		BufferedReader reader = new BufferedReader( new InputStreamReader( new FileInputStream( stopwordsFile ), "UTF-8" ) );
		String line;
		while ( ( line = reader.readLine() ) != null ) {
			line = line.trim();
			if ( line.length() > 0 ) {
				this.stopwords.add( line );
			}
		}
		reader.close();
	}
	
	public List<SearchResult> search( String field, List<String> terms, ScoringFunction scoreFunc, int top ) throws IOException {
		
		Map<String, Double> qfreqs = new TreeMap<>();
		for ( String term : terms ) {
			if ( !stopwords.contains( term ) ) {
				qfreqs.put( term, qfreqs.getOrDefault( term, 0.0 ) + 1 );
			}
		}
		
		List<PostingsEnum> postings = new ArrayList<>();
		List<Double> weights = new ArrayList<>();
		List<Double> tfcs = new ArrayList<>();
		for ( String term : qfreqs.keySet() ) {
			PostingsEnum list = MultiFields.getTermDocsEnum( index, field, new BytesRef( term ), PostingsEnum.FREQS );
			if ( list.nextDoc() != PostingsEnum.NO_MORE_DOCS ) {
				postings.add( list );
				weights.add( qfreqs.get( term ) / terms.size() );
				tfcs.add( 1.0 * index.totalTermFreq( new Term( field, term ) ) );
			}
		}
		return search( postings, weights, tfcs, getDocLengthReader( field ), index.getSumTotalTermFreq( field ), scoreFunc, top );
	}
	
	private List<SearchResult> search( List<PostingsEnum> postings, List<Double> weights, List<Double> tfcs, DocLengthReader doclen, double cl, ScoringFunction scoreFunc, int top ) throws IOException {
		
		PriorityQueue<SearchResult> topResults = new PriorityQueue<>( ( r1, r2 ) -> {
			int cp = r1.getScore().compareTo( r2.getScore() );
			if ( cp == 0 ) {
				cp = r1.getDocid() - r2.getDocid();
			}
			return cp;
		} );
		
		List<Double> tfs = new ArrayList<>( weights.size() );
		for ( int ix = 0; ix < weights.size(); ix++ ) {
			tfs.add( 0.0 );
		}
		while ( true ) {
			
			int docid = Integer.MAX_VALUE;
			for ( PostingsEnum posting : postings ) {
				if ( posting.docID() != PostingsEnum.NO_MORE_DOCS && posting.docID() < docid ) {
					docid = posting.docID();
				}
			}
			
			if ( docid == Integer.MAX_VALUE ) {
				break;
			}
			
			int ix = 0;
			for ( PostingsEnum posting : postings ) {
				if ( docid == posting.docID() ) {
					tfs.set( ix, 1.0 * posting.freq() );
					posting.nextDoc();
				} else {
					tfs.set( ix, 0.0 );
				}
				ix++;
			}
			double score = scoreFunc.score( weights, tfs, tfcs, doclen.getLength( docid ), cl );
			
			if ( topResults.size() < top ) {
				topResults.add( new SearchResult( docid, null, score ) );
			} else {
				SearchResult result = topResults.peek();
				if ( score > result.getScore() ) {
					topResults.poll();
					topResults.add( new SearchResult( docid, null, score ) );
				}
			}
		}
		
		List<SearchResult> results = new ArrayList<>( topResults.size() );
		results.addAll( topResults );
		Collections.sort( results, ( o1, o2 ) -> o2.getScore().compareTo( o1.getScore() ) );
		return results;
	}
	
	public DocLengthReader getDocLengthReader( String field ) throws IOException {
		DocLengthReader doclen = doclens.get( field );
		if ( doclen == null ) {
			doclen = new FileDocLengthReader( this.dirBase, field );
			doclens.put( field, doclen );
		}
		return doclen;
	}
	
	public void close() throws IOException {
		index.close();
		dirLucene.close();
		for ( DocLengthReader doclen : doclens.values() ) {
			doclen.close();
		}
	}
	
	public interface ScoringFunction {
		
		/**
		 * @param weights Weight of the query terms, e.g., P(t|q) or c(t,q).
		 * @param tfs     The frequencies of the query terms in documents.
		 * @param tfcs    The frequencies of the query terms in the corpus.
		 * @param dl      The length of the document.
		 * @param cl      The length of the whole corpus.
		 * @return
		 */
		double score( List<Double> weights, List<Double> tfs, List<Double> tfcs, double dl, double cl );
	}
	
	public static class QLJMSmoothing implements ScoringFunction {
		
		protected double lambda;
		
		public QLJMSmoothing( double lambda ) {
			this.lambda = lambda;
		}
		
		public double score( List<Double> weights, List<Double> tfs, List<Double> tfcs, double dl, double cl ) {
			double result=0;

			for (int i=0;i<weights.size();i++)
			{
				double docStats=(1-lambda)*(tfs.get(i)/dl);
				double corpusStats=lambda*(tfcs.get(i)/cl);
				result+=weights.get(i)*Math.log(docStats+corpusStats);
			}

			return result;
		}
	}
	
	public static class QLDirichletSmoothing implements ScoringFunction {
		
		protected double mu;
		
		public QLDirichletSmoothing( double mu ) {
			this.mu = mu;
		}
		
		public double score( List<Double> weights, List<Double> tfs, List<Double> tfcs, double dl, double cl ) {
			double result=0;

			for (int i=0;i<weights.size();i++)
			{
				double numerator=tfs.get(i)+mu*(tfcs.get(i)/cl);
				double denominator=dl+mu;

				result+=weights.get(i)*Math.log(numerator/denominator);
			}

			return result;

		}
	}
	
}
