package org.janusgraph.graphdb.olap;

import com.google.common.base.Preconditions;
import org.janusgraph.core.JanusGraphFactory;
import org.janusgraph.core.JanusGraph;
import org.janusgraph.core.JanusGraphVertex;
import org.janusgraph.diskstorage.EntryList;
import org.janusgraph.diskstorage.StaticBuffer;
import org.janusgraph.diskstorage.configuration.BasicConfiguration;
import org.janusgraph.diskstorage.configuration.Configuration;
import org.janusgraph.diskstorage.keycolumnvalue.SliceQuery;
import org.janusgraph.diskstorage.keycolumnvalue.scan.ScanJob;
import org.janusgraph.diskstorage.keycolumnvalue.scan.ScanMetrics;
import org.janusgraph.diskstorage.util.BufferUtil;
import org.janusgraph.graphdb.database.StandardJanusGraph;
import org.janusgraph.graphdb.idmanagement.IDManager;
import org.janusgraph.graphdb.query.Query;
import org.janusgraph.graphdb.relations.RelationCache;
import org.janusgraph.graphdb.transaction.StandardJanusGraphTx;
import org.janusgraph.graphdb.transaction.StandardTransactionBuilder;
import org.janusgraph.graphdb.types.system.BaseKey;
import org.janusgraph.graphdb.vertices.PreloadedVertex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * @author Matthias Broecheler (me@matthiasb.com)
 */
public class VertexJobConverter implements ScanJob {

    private static final Logger log =
            LoggerFactory.getLogger(VertexJobConverter.class);

    protected static final SliceQuery VERTEX_EXISTS_QUERY = new SliceQuery(BufferUtil.zeroBuffer(1),BufferUtil.oneBuffer(4)).setLimit(1);

    public static final String GHOST_VERTEX_COUNT = "ghost-vertices";
    /**
     * Number of result sets that got (possibly) truncated due to an applied query limit
     */
    public static final String TRUNCATED_ENTRY_LISTS = "truncated-results";

    protected final GraphProvider graph;
    protected final VertexScanJob job;

    protected StandardJanusGraphTx tx;
    private IDManager idManager;

    protected VertexJobConverter(JanusGraph graph, VertexScanJob job) {
        Preconditions.checkArgument(job!=null);
        this.graph = new GraphProvider();
        if (graph!=null) this.graph.setGraph(graph);
        this.job = job;
    }

    protected VertexJobConverter(VertexJobConverter copy) {
        this.graph = new GraphProvider();
        if (copy.graph.isProvided()) this.graph.setGraph(copy.graph.get());
        this.job = copy.job.clone();
    }

    public static ScanJob convert(JanusGraph graph, VertexScanJob vertexJob) {
        return new VertexJobConverter(graph,vertexJob);
    }

    public static ScanJob convert(VertexScanJob vertexJob) {
        return new VertexJobConverter(null,vertexJob);
    }

    public static StandardJanusGraphTx startTransaction(StandardJanusGraph graph) {
        StandardTransactionBuilder txb = graph.buildTransaction().readOnly();
        txb.setPreloadedData(true);
        txb.checkInternalVertexExistence(false);
        txb.dirtyVertexSize(0);
        txb.vertexCacheSize(0);
        return (StandardJanusGraphTx)txb.start();
    }

    @Override
    public void workerIterationStart(Configuration jobConfig, Configuration graphConfig, ScanMetrics metrics) {
        graph.initializeGraph(graphConfig);
        idManager = graph.get().getIDManager();
        try {
            tx = startTransaction(graph.get());
            job.workerIterationStart(graph.get(), jobConfig, metrics);
        } catch (Throwable e) {
            close();
            throw e;
        }
    }

    private void close() {
        if (null != tx && tx.isOpen())
            tx.rollback();
        graph.close();
    }

    @Override
    public void workerIterationEnd(ScanMetrics metrics) {
        job.workerIterationEnd(metrics);
        close();
    }

    @Override
    public void process(StaticBuffer key, Map<SliceQuery, EntryList> entries, ScanMetrics metrics) {
        long vertexId = getVertexId(key);
        assert entries.get(VERTEX_EXISTS_QUERY)!=null;
        if (isGhostVertex(vertexId, entries.get(VERTEX_EXISTS_QUERY))) {
            metrics.incrementCustom(GHOST_VERTEX_COUNT);
            return;
        }
        JanusGraphVertex vertex = tx.getInternalVertex(vertexId);
        Preconditions.checkArgument(vertex instanceof PreloadedVertex,
                "The bounding transaction is not configured correctly");
        PreloadedVertex v = (PreloadedVertex)vertex;
        v.setAccessCheck(PreloadedVertex.OPENSTAR_CHECK);
        for (Map.Entry<SliceQuery,EntryList> entry : entries.entrySet()) {
            SliceQuery sq = entry.getKey();
            if (sq.equals(VERTEX_EXISTS_QUERY)) continue;
            EntryList entryList = entry.getValue();
            if (entryList.size()>=sq.getLimit()) metrics.incrementCustom(TRUNCATED_ENTRY_LISTS);
            v.addToQueryCache(sq.updateLimit(Query.NO_LIMIT),entryList);
        }
        job.process(v, metrics);
    }

    protected boolean isGhostVertex(long vertexId, EntryList firstEntries) {
        if (idManager.isPartitionedVertex(vertexId) && !idManager.isCanonicalVertexId(vertexId)) return false;

        RelationCache relCache = tx.getEdgeSerializer().parseRelation(
                firstEntries.get(0),true,tx);
        return relCache.typeId != BaseKey.VertexExists.longId();
    }

    @Override
    public List<SliceQuery> getQueries() {
        try {
            QueryContainer qc = new QueryContainer(tx);
            job.getQueries(qc);

            List<SliceQuery> slices = new ArrayList<>();
            slices.add(VERTEX_EXISTS_QUERY);
            slices.addAll(qc.getSliceQueries());
            return slices;
        } catch (Throwable e) {
            close();
            throw e;
        }
    }

    @Override
    public Predicate<StaticBuffer> getKeyFilter() {
        return buffer -> {
            long vertexId = getVertexId(buffer);
            if (IDManager.VertexIDType.Invisible.is(vertexId)) return false;
            else return true;
        };
    }

    @Override
    public VertexJobConverter clone() {
        return new VertexJobConverter(this);
    }

    protected long getVertexId(StaticBuffer key) {
        return idManager.getKeyID(key);
    }

    public static class GraphProvider {

        private StandardJanusGraph graph=null;
        private boolean provided=false;

        public void setGraph(JanusGraph graph) {
            Preconditions.checkArgument(graph!=null && graph.isOpen(),"Need to provide open graph");
            this.graph = (StandardJanusGraph)graph;
            provided = true;
        }

        public void initializeGraph(Configuration config) {
            if (!provided) {
                this.graph = (StandardJanusGraph) JanusGraphFactory.open((BasicConfiguration) config);
            }
        }

        public void close() {
            if (!provided && null != graph && graph.isOpen()) {
                graph.close();
                graph=null;
            }
        }

        public boolean isProvided() {
            return provided;
        }

        public final StandardJanusGraph get() {
            Preconditions.checkState(graph!=null);
            return graph;
        }


    }

}
