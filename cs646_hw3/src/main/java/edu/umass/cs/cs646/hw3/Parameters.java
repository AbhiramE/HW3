package edu.umass.cs.cs646.hw3;

import edu.umass.cs.cs646.utils.EvalUtils;
import edu.umass.cs.cs646.utils.LuceneUtils;
import edu.umass.cs.cs646.utils.SearchResult;
import org.apache.commons.math3.stat.StatUtils;
import org.apache.lucene.analysis.Analyzer;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Valar Dohaeris 10/22/16.
 */
public class Parameters {

    public static void main(String[] args) {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter("testResultsDirichlet"))) {

            String pathIndex = "/home/abhiram/codebase/InformationRetrieval/HW3/index_lucene_robust04";
            Analyzer analyzer = LuceneUtils.getAnalyzer(LuceneUtils.Stemming.Krovetz);

            String pathQueries = "/home/abhiram/codebase/InformationRetrieval/HW3/training_queries";
            String pathQrels = "/home/abhiram/codebase/InformationRetrieval/HW3/qrels";
            String pathStopwords = "/home/abhiram/codebase/InformationRetrieval/HW3/stopwords_inquery";

            String field_docno = "docno";
            String field_search = "content";

            MySearcher searcher = new MySearcher(pathIndex);
            searcher.setStopwords(pathStopwords);

            Map<String, String> queries = EvalUtils.loadQueries(pathQueries);
            Map<String, Set<String>> qrels = EvalUtils.loadQrels(pathQrels);

            int top = 1000;
            int mu = 1000;
            bw.write("mu \tMeanP@10 \t\tAPP@10");
            bw.newLine();

            mu = (mu * 100) / (100);

            MySearcher.ScoringFunction scoreFunc = new MySearcher.QLDirichletSmoothing(mu);

            double[] p10 = new double[queries.size()];
            double[] ap = new double[queries.size()];

            double total = 0;
            int ix = 0;
            for (String qid : queries.keySet()) {

                String query = queries.get(qid);
                List<String> terms = LuceneUtils.tokenize(query, analyzer);
                String[] termsarray = new String[terms.size()];
                for (int k = 0; k < termsarray.length; k++) {
                    termsarray[k] = terms.get(k);
                }

                List<SearchResult> results = searcher.search(field_search, terms, scoreFunc, top);
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

            searcher.close();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
