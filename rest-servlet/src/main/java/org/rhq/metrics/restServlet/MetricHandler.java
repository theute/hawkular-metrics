package org.rhq.metrics.restServlet;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;
import javax.ws.rs.core.GenericEntity;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import gnu.trove.map.TLongObjectMap;
import gnu.trove.map.hash.TLongObjectHashMap;

import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.jboss.resteasy.annotations.GZIP;

import org.rhq.metrics.core.MetricsService;
import org.rhq.metrics.core.NumericMetric;
import org.rhq.metrics.core.RawNumericMetric;

import static java.lang.Double.NaN;

/**
 * Interface to deal with metrics
 * @author Heiko W. Rupp
 */
@Path("/")
public class MetricHandler {

    private static final Logger logger = LoggerFactory.getLogger(MetricHandler.class);
    private static final long EIGHT_HOURS = 8L*60L*60L*1000L; // 8 Hours in milliseconds

    @Inject
    private MetricsService metricsService;

    public MetricHandler() {
        if (logger.isDebugEnabled()) {
            logger.debug("MetricHandler instantiated");
        }
    }

	@GET
    @POST
	@Path("/ping")
	@Consumes({ "application/json", "application/xml" })
	@Produces({ "application/json", "application/xml" })
	public Response ping() {
		Map<String, String> reply = new HashMap<String, String>();
		reply.put("pong", new Date().toString());

		Response.ResponseBuilder builder = Response.ok(reply);
		return builder.build();
	}

    @POST
    @Path("/metrics/{id}")
    @Consumes({"application/json","application/xml"})
    public void addMetric(@Suspended AsyncResponse asyncResponse, @PathParam("id") String id, IdDataPoint dataPoint) {
        addData(asyncResponse, ImmutableSet.of(new RawNumericMetric(id, dataPoint.getValue(),
            dataPoint.getTimestamp())));
    }

    @POST
    @Path("/metrics")
    @Consumes({"application/json","application/xml"})
    public void addMetrics(@Suspended AsyncResponse asyncResponse, Collection<IdDataPoint> dataPoints) {

        Set<RawNumericMetric> rawSet = new HashSet<>(dataPoints.size());
        for (IdDataPoint dataPoint : dataPoints) {
            RawNumericMetric rawMetric = new RawNumericMetric(dataPoint.getId(), dataPoint.getValue(),
                dataPoint.getTimestamp());
            rawSet.add(rawMetric);
        }

        addData(asyncResponse, rawSet);
    }

    private void addData(final AsyncResponse asyncResponse, Set<RawNumericMetric> rawData) {
        ListenableFuture<Map<RawNumericMetric,Throwable>> future = metricsService.addData(rawData);
        Futures.addCallback(future, new FutureCallback<Map<RawNumericMetric, Throwable>>() {
            @Override
            public void onSuccess(Map<RawNumericMetric, Throwable> errors) {
                Response jaxrs = Response.ok().type(MediaType.APPLICATION_JSON_TYPE).build();
                asyncResponse.resume(jaxrs);
            }

            @Override
            public void onFailure(Throwable t) {
                asyncResponse.resume(t);
            }
        });
    }

    @GZIP
    @GET
    @Path("/metrics/{id}")
    @Produces({"application/json","application/xml"})
    public void getDataForId(@Suspended final AsyncResponse asyncResponse, @PathParam("id") final String id,
        @QueryParam("start") Long start, @QueryParam("end") Long end, @QueryParam("buckets") final int numberOfBuckets,
        @QueryParam("bucketWidthSeconds") final int bucketWidthSeconds,
        @QueryParam("skipEmpty") @DefaultValue("false") final boolean skipEmpty,
        @QueryParam("bucketCluster") @DefaultValue("true") final boolean bucketCluster) {

        // The idExists call is commented out since the Cassandra based impl is a no-op. We
        // need to decided whether or not this check is really necessary. There isn't really
        // an efficient way to do this in Cassandra unless we either query all keys or
        // introduce new schema to support this method.
        //
        // jsanda
//        if (!metricsService.idExists(id)) {
//            asyncResponse.resume(Response.status(404).entity("Id [" + id + "] not found. ").build());
////            builder = Response.status(404).entity("Id [" + id + "] not found. ");
////            return builder.build();
//        }

        long now = System.currentTimeMillis();
        if (start==null) {
            start= now -EIGHT_HOURS;
        }
        if (end==null) {
            end = now;
        }

        final ListenableFuture<List<RawNumericMetric>> future = metricsService.findData(id, start, end);

        final Long finalStart = start;
        final Long finalEnd = end;
        Futures.addCallback(future, new FutureCallback<List<RawNumericMetric>>() {
            @Override
            public void onSuccess(List<RawNumericMetric> metrics) {
                if (numberOfBuckets == 0) {
                    // Normal case of raw metrics
                    List<DataPoint> points = new ArrayList<>(metrics.size());
                    for (NumericMetric item : metrics) {
                        DataPoint point = new DataPoint(item.getTimestamp(), item.getAvg());
                        points.add(point);
                    }
                    GenericEntity<List<DataPoint>> list = new GenericEntity<List<DataPoint>>(points) {};
                    Response jaxrs = Response.ok(list).type(MediaType.APPLICATION_JSON_TYPE).build();
                    asyncResponse.resume(jaxrs);

                } else {
                    // User wants data in buckets
                    List<BucketDataPoint> points = new ArrayList<>(numberOfBuckets);
                    if (bucketWidthSeconds == 0) {
                        // we will have numberOfBuckets buckets over the whole time span

                        long bucketsize = (finalEnd - finalStart) / numberOfBuckets;
                        for (int i = 0; i < numberOfBuckets; i++) {
                            long startTime = finalStart + i*bucketsize;

                            BucketDataPoint point = createPointInSimpleBucket(id, startTime, bucketsize, metrics);
                            if (!skipEmpty || !point.isEmpty()) {
                                points.add(point);
                            }
                        }
                    } else {
                        // we will have numberOfBuckets buckets, but with a fixed with. Buckets will thus
                        // be reused over time after (numberOfBuckets*bucketWidthSeconds seconds)
                        long totalLength = numberOfBuckets * bucketWidthSeconds * 1000L ;

                        // find the minimum ts
                        long minTs = Long.MAX_VALUE;
                        for (RawNumericMetric metric : metrics) {
                            if (metric.getTimestamp() < minTs) {
                                minTs = metric.getTimestamp();
                            }
                        }

                        TLongObjectMap<List<RawNumericMetric>> buckets = new TLongObjectHashMap<>(numberOfBuckets);

                        for (RawNumericMetric metric : metrics) {
                            long bucket = metric.getTimestamp() - minTs;
                            bucket = bucket % totalLength;
                            bucket = bucket / (bucketWidthSeconds * 1000L);
                            List<RawNumericMetric> tmpList = buckets.get(bucket);
                            if (tmpList == null) {
                                tmpList = new ArrayList<>();
                                buckets.put(bucket, tmpList);
                            }
                            tmpList.add(metric);
                        }
                        if (bucketCluster) {
                            // Now that stuff is in buckets - we need to "flatten" them out.
                            // As we collapse stuff from a lot of input timestamps into some
                            // buckets, we only use a relative time for the bucket timestamps.
                            for (int i = 0; i < numberOfBuckets; i++) {
                                List<RawNumericMetric> tmpList = buckets.get(i);
                                BucketDataPoint point;
                                if (tmpList == null) {
                                    if (!skipEmpty) {
                                        point = new BucketDataPoint(id, i * bucketWidthSeconds * 1000L, NaN, NaN, NaN);
                                        points.add(point);
                                    }
                                } else {
                                    point = getBucketDataPoint(tmpList.get(0).getId(),
                                        i * bucketWidthSeconds * 1000L, tmpList);
                                    points.add(point);
                                }

                            }
                        } else {
                            // We want to keep the raw values, but put them into clusters anyway
                            // without collapsing them into a single min/avg/max tuple
                            for (int i = 0; i < numberOfBuckets; i++) {
                                List<RawNumericMetric> tmpList = buckets.get(i);
                                BucketDataPoint point;
                                if (tmpList!=null) {
                                    for (RawNumericMetric metric : tmpList) {
                                        point = new BucketDataPoint(id, // TODO could be simple data points
                                            i * bucketWidthSeconds * 1000L, NaN,metric.getValue(),NaN);
                                        point.setValue(metric.getValue());
                                        points.add(point);
                                    }
                                }

                            }

                        }
                    }

                    GenericEntity<List<BucketDataPoint>> list = new GenericEntity<List<BucketDataPoint>>(points) {};
                    Response jaxrs = Response.ok(list).type(MediaType.APPLICATION_JSON_TYPE).build();
                    asyncResponse.resume(jaxrs);
                }

            }

            @Override
            public void onFailure(Throwable t) {
                asyncResponse.resume(t);
            }
        });
    }


    @GZIP
    @GET
    @Path("/metrics")
    @Produces({"application/json","application/xml"})
    public Response listMetrics(@QueryParam("q") String filter) {

        List<String> names = ServiceKeeper.getInstance().service.listMetrics();

        final List<SimpleLink> listWithLinks = new ArrayList<>(names.size());
        for (String name : names) {
            if ((filter == null || filter.isEmpty()) || (name.contains(filter))) {
                SimpleLink link = new SimpleLink("metrics", "/rhq-metrics/metrics/" + name + "/", name);
                listWithLinks.add(link);
            }
        }

        GenericEntity<List<SimpleLink>> list = new GenericEntity<List<SimpleLink>>(listWithLinks) {} ;
        Response.ResponseBuilder builder = Response.ok(list);

        return builder.build();
    }

    private BucketDataPoint createPointInSimpleBucket(String id, long startTime, long bucketsize,
                                                      List<RawNumericMetric> metrics) {
        List<RawNumericMetric> bucketMetrics = new ArrayList<>(metrics.size());
        // Find matching metrics
        for (NumericMetric raw : metrics) {
            if (raw.getTimestamp() >= startTime && raw.getTimestamp() < startTime + bucketsize) {
                bucketMetrics.add((RawNumericMetric) raw);
            }
        }

        return getBucketDataPoint(id, startTime, bucketMetrics);
    }

    private BucketDataPoint getBucketDataPoint(String id, long startTime, List<RawNumericMetric> bucketMetrics) {
        Double min = null;
        Double max = null;
        double sum = 0;
        for (RawNumericMetric raw : bucketMetrics) {
            if (max==null || raw.getValue() > max) {
                max = raw.getValue();
            }
            if (min==null || raw.getValue() < min) {
                min = raw.getValue();
            }
            sum += raw.getValue();
        }
        double avg = bucketMetrics.size()>0 ? sum / bucketMetrics.size() : NaN;
        if (min == null) {
            min = NaN;
        }
        if (max == null) {
            max = NaN;
        }
        BucketDataPoint result = new BucketDataPoint(id,startTime,min, avg,max);

        return result;
    }

}
