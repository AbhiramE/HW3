package edu.umass.cs.cs646.utils;

import edu.umass.cs.cs646.hw3.MySearcher;
import javafx.util.Pair;
import org.apache.commons.math3.stat.StatUtils;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.*;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class ReRanker extends AbstractQLSearcher {

    public static void main(String[] args) {
        try {


            String pathIndex = "/home/abhiram/codebase/InformationRetrieval/HW3/index_lucene_robust04";
            Analyzer analyzer = LuceneUtils.getAnalyzer(LuceneUtils.Stemming.Krovetz);

            String pathQueries = "/home/abhiram/codebase/InformationRetrieval/HW3/queries";
            String pathQrels = "/home/abhiram/codebase/InformationRetrieval/HW3/qrels";
            String pathStopwords = "/home/abhiram/codebase/InformationRetrieval/HW3/stopwords_inquery";

            String field_docno = "docno";
            String field_search = "content";

            ReRanker searcher = new ReRanker(pathIndex);
            searcher.setStopwords(pathStopwords);

            Map<String, String> queries = EvalUtils.loadQueries(pathQueries);
            Map<String, Set<String>> qrels = EvalUtils.loadQrels(pathQrels);

            int k = 10;
            int n= 100;
            int top=1000;
            double mu = 1000;
            double muFb=0;

            String path="/home/abhiram/codebase/InformationRetrieval/HW3/LamdaForRM3";
            BufferedWriter bw=new BufferedWriter(new FileWriter(path));
            String line="Lamda\t\t MeanP10 \t\t MeanAP";
            bw.write(line);
            bw.newLine();

            for (int m=0;m<=5000;m+=500) {

                double[] p10 = new double[queries.size()];
                double[] ap = new double[queries.size()];

                int ix = 0;
                for (String qid : queries.keySet()) {

                    String query = queries.get(qid);
                    List<String> terms = LuceneUtils.tokenize(query, analyzer);
                    String[] termsarray = new String[terms.size()];
                    for (int i = 0; i < termsarray.length; i++) {
                        termsarray[i] = terms.get(i);
                    }


                    List<SearchResult> resultsQL=searcher.search(field_search,terms,mu,m);
                    Map<String, Double> resultRM1 = ReRanker.estimateQueryModelRM1(searcher.index, searcher.getDocLengthReader(field_search), field_search, terms, mu, muFb, k, n);
                    SearchResult.dumpDocno(searcher.index, field_docno, results);

                    p10[ix] = EvalUtils.precision(results, qrels.get(qid), 10);
                    ap[ix] = EvalUtils.avgPrec(results, qrels.get(qid), top);

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
                        StatUtils.mean(p10),
                        StatUtils.mean(ap)
                );

                line=lamda+"\t\t"+StatUtils.mean(p10)+"\t\t"+StatUtils.mean(ap);
                bw.write(line);
                bw.newLine();
            }

            searcher.close();
            bw.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    protected File dirBase;
    protected Directory dirLucene;
    public IndexReader index;
    public Map<String, DocLengthReader> doclens;

    public ReRanker(String dirPath) throws IOException {
        this(new File(dirPath));
    }

    public ReRanker(File dirBase) throws IOException {
        this.dirBase = dirBase;
        this.dirLucene = FSDirectory.open(this.dirBase.toPath());
        this.index = DirectoryReader.open(dirLucene);
        this.doclens = new HashMap<>();
    }

    public IndexReader getIndex() {
        return this.index;
    }

    public PostingList getPosting(String field, String term) throws IOException {
        return new LuceneTermPostingList(index, field, term);
    }

    public DocLengthReader getDocLengthReader(String field) throws IOException {
        DocLengthReader doclen = doclens.get(field);
        if (doclen == null) {
            doclen = new FileDocLengthReader(this.dirBase, field);
            doclens.put(field, doclen);
        }
        return doclen;
    }

    public void close() throws IOException {
        index.close();
        dirLucene.close();
        for (DocLengthReader doclen : doclens.values()) {
            doclen.close();
        }
    }


    public static Map<String, Double> estimateQueryModelRM1(IndexReader index, DocLengthReader docLengthReader, String field, List<String> terms, double mu1, double mu2, int numfbdocs, int numfbterms) throws IOException {

        String pathStopwords = "/home/abhiram/codebase/InformationRetrieval/HW3/stopwords_inquery";
        String pathIndex = "/home/abhiram/codebase/InformationRetrieval/HW3/index_lucene_robust04";

        HashSet<String> vocabulary = new HashSet<>();
        Map<String,Double> weights = new HashMap<>();
        Map<String, Double> result = new HashMap<>();
        double totaldenominator = 0;

        ReRanker searcher = new ReRanker(pathIndex);
        searcher.setStopwords(pathStopwords);
        List<SearchResult> resultsQL = searcher.search(field, terms, mu1, numfbdocs);

        //Build vocabulary
        List<HashMap<String,Double>> scores=new ArrayList<>();
        for (double i= 0; i < numfbdocs && (i<resultsQL.size()); i++) {
            int docId=resultsQL.get((int) i).getDocid();
            Terms vector = index.getTermVector(docId, field);
            double dl=docLengthReader.getLength(docId);
            TermsEnum termVectors = vector.iterator();
            BytesRef term;
            HashMap<String,Double> hashMap=new HashMap<>();

            while ((term = termVectors.next()) != null) {
                String termString = term.utf8ToString();

                if(!searcher.isStopwords(termString)) {
                    vocabulary.add(termString);
                    PostingsEnum list = MultiFields.getTermDocsEnum( index, field, term, PostingsEnum.FREQS );
                    while (docId!=list.docID())
                        list.nextDoc();

                    double score=score((double)list.freq(), (double)index.totalTermFreq( new Term( field, term )),
                            mu2,dl,index.getSumTotalTermFreq(field));
                    hashMap.put(termString,hashMap.getOrDefault(termString,0.0)+score);
                }
            }
            scores.add(hashMap);
        }

        for (String term : vocabulary) {

            for( int i=0;(i<numfbdocs) && (i<resultsQL.size()); i++ ){

                double pqd = Math.exp( resultsQL.get(i).score);
                Double ptd = Math.exp(scores.get(i).getOrDefault(term,0.0));

                double weight_fbdoc;
                if(ptd==1)
                    weight_fbdoc=0;
                else
                    weight_fbdoc= pqd*ptd;
                weights.put( term, weights.getOrDefault(term, 0.0)+weight_fbdoc );
            }
            totaldenominator+=weights.get(term);
        }


        for (String term : vocabulary)
            result.put(term, weights.get(term) / totaldenominator);

        result = sortAndGetTopElements(result,numfbterms);

        return result;
    }

    private static <K, V> Map<K, V> sortAndGetTopElements(Map<K, V> map,int terms) {
        List<Map.Entry<K, V>> list = new LinkedList<>(map.entrySet());
        Collections.sort(list, (o1, o2) -> ((Comparable<V>) o2.getValue()).compareTo(o1.getValue()));

        Map<K, V> result = new LinkedHashMap<>();
        int count=0;
        for (Map.Entry<K, V> entry : list) {
            count++;
            if(count>terms)
                break;
            result.put(entry.getKey(), entry.getValue());
        }

        return result;
    }

    public static double score( Double tf, Double tfc, double mu, double dl, double cl ) {
        double result=0;
        double weight=1;

        double numerator=tf+mu*(tfc/cl);
        double denominator=dl+mu;
        result+=weight*Math.log(numerator/denominator);

        return result;

    }


}
