/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.search.dfs;

import org.apache.lucene.index.Term;
import org.apache.lucene.search.CollectionStatistics;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.TermStatistics;
import org.apache.lucene.search.TopScoreDocCollector;
import org.elasticsearch.index.query.SearchExecutionContext;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.internal.SearchContext;
import org.elasticsearch.search.profile.query.CollectorResult;
import org.elasticsearch.search.profile.query.InternalProfileCollector;
import org.elasticsearch.search.rescore.RescoreContext;
import org.elasticsearch.search.vectors.KnnSearchBuilder;
import org.elasticsearch.search.vectors.KnnVectorQueryBuilder;
import org.elasticsearch.tasks.TaskCancelledException;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DFS phase of a search request, used to make scoring 100% accurate by collecting additional info from each shard before the query phase.
 * The additional information is used to better compare the scores coming from all the shards, which depend on local factors (e.g. idf).
 *
 * When a kNN search is provided alongside the query, the DFS phase is also used to gather the top k candidates from each shard. Then the
 * global top k hits are passed on to the query phase.
 */
public class DfsPhase {

    public void execute(SearchContext context) {
        try {
            collectStatistics(context);
            executeKnnVectorQuery(context);
        } catch (Exception e) {
            throw new DfsPhaseExecutionException(context.shardTarget(), "Exception during dfs phase", e);
        }
    }

    private void collectStatistics(SearchContext context) throws IOException {
        Map<String, CollectionStatistics> fieldStatistics = new HashMap<>();
        Map<Term, TermStatistics> stats = new HashMap<>();

        IndexSearcher searcher = new IndexSearcher(context.searcher().getIndexReader()) {
            @Override
            public TermStatistics termStatistics(Term term, int docFreq, long totalTermFreq) throws IOException {
                if (context.isCancelled()) {
                    throw new TaskCancelledException("cancelled");
                }
                TermStatistics ts = super.termStatistics(term, docFreq, totalTermFreq);
                if (ts != null) {
                    stats.put(term, ts);
                }
                return ts;
            }

            @Override
            public CollectionStatistics collectionStatistics(String field) throws IOException {
                if (context.isCancelled()) {
                    throw new TaskCancelledException("cancelled");
                }
                CollectionStatistics cs = super.collectionStatistics(field);
                if (cs != null) {
                    fieldStatistics.put(field, cs);
                }
                return cs;
            }
        };

        searcher.createWeight(context.rewrittenQuery(), ScoreMode.COMPLETE, 1);
        for (RescoreContext rescoreContext : context.rescore()) {
            for (Query query : rescoreContext.getQueries()) {
                searcher.createWeight(context.searcher().rewrite(query), ScoreMode.COMPLETE, 1);
            }
        }

        Term[] terms = stats.keySet().toArray(new Term[0]);
        TermStatistics[] termStatistics = new TermStatistics[terms.length];
        for (int i = 0; i < terms.length; i++) {
            termStatistics[i] = stats.get(terms[i]);
        }

        context.dfsResult()
            .termsStatistics(terms, termStatistics)
            .fieldStatistics(fieldStatistics)
            .maxDoc(context.searcher().getIndexReader().maxDoc());
    }

    private void executeKnnVectorQuery(SearchContext context) throws IOException {
        SearchSourceBuilder source = context.request().source();
        if (source == null || source.knnSearch() == null) {
            return;
        }

        SearchExecutionContext searchExecutionContext = context.getSearchExecutionContext();
        KnnSearchBuilder knnSearch = context.request().source().knnSearch();
        KnnVectorQueryBuilder knnVectorQueryBuilder = knnSearch.toQueryBuilder();

        if (context.request().getAliasFilter().getQueryBuilder() != null) {
            knnVectorQueryBuilder.addFilterQuery(context.request().getAliasFilter().getQueryBuilder());
        }

        Query query = searchExecutionContext.toQuery(knnVectorQueryBuilder).query();
        TopScoreDocCollector topScoreDocCollector = TopScoreDocCollector.create(knnSearch.k(), Integer.MAX_VALUE);
        Collector collector = topScoreDocCollector;

        if (context.getProfilers() != null) {
            InternalProfileCollector ipc = new InternalProfileCollector(
                topScoreDocCollector,
                CollectorResult.REASON_SEARCH_TOP_HITS,
                List.of()
            );
            context.getProfilers().getCurrentQueryProfiler().setCollector(ipc);
            collector = ipc;
        }

        context.searcher().search(query, collector);
        DfsKnnResults knnResults = new DfsKnnResults(topScoreDocCollector.topDocs().scoreDocs);
        context.dfsResult().knnResults(knnResults);

        if (context.getProfilers() != null) {
            context.dfsResult().profileResult(context.getProfilers().buildDfsPhaseResults());
        }
    }
}
