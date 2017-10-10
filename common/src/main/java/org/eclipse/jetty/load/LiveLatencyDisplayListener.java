package org.eclipse.jetty.load;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.Recorder;
import org.eclipse.jetty.toolchain.perf.HistogramSnapshot;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.mortbay.jetty.load.generator.LoadGenerator;
import org.mortbay.jetty.load.generator.Resource;
import org.mortbay.jetty.load.generator.listeners.CollectorInformations;

import java.io.IOException;
import java.io.StringWriter;
import java.util.concurrent.TimeUnit;

public class LiveLatencyDisplayListener
    implements Resource.NodeListener, LoadGenerator.BeginListener, LoadGenerator.EndListener, Runnable
{
    private static final Logger LOGGER = Log.getLogger( LiveLatencyDisplayListener.class );

    private final Recorder recorder;

    private final Histogram histogram;

    private Histogram interval;

    private Monitor.Start begin;

    private Monitor.Start start;

    private long currentQps, finalQps;

    private ServerInfo serverInfo;

    public LiveLatencyDisplayListener()
    {
        this( TimeUnit.MICROSECONDS.toNanos( 1 ), TimeUnit.SECONDS.toNanos( 60 ), 3 );
    }

    public LiveLatencyDisplayListener( long low, long high, int digits )
    {
        histogram = new Histogram( low, high, digits );
        recorder = new Recorder( low, high, digits );
        start = Monitor.start();
    }

    public LiveLatencyDisplayListener serverInfo( ServerInfo serverInfo )
    {
        this.serverInfo = serverInfo;
        return this;
    }

    public void onResourceNode( Resource.Info info )
    {
        long responseTime = info.getResponseTime() - info.getRequestTime();
        recorder.recordValue( responseTime );
    }

    public void run()
    {
        Monitor.Stop stop = start.stop();

        interval = recorder.getIntervalHistogram( interval );
        histogram.add( interval );

        long totalRequestCommitted = interval.getTotalCount();
        long start = interval.getStartTimeStamp();
        long end = interval.getEndTimeStamp();
        long timeInSeconds = TimeUnit.SECONDS.convert( end - start, TimeUnit.MILLISECONDS );
        currentQps = totalRequestCommitted / timeInSeconds;

        LOGGER.info( String.format( "response time: min/max=%d/%d \u00B5s, jit=%d ms, qps=%s, cpu=%.2f%%%n",
                                    TimeUnit.NANOSECONDS.toMicros( interval.getMinValue() ),
                                    TimeUnit.NANOSECONDS.toMicros( interval.getMaxValue() ), stop.deltaJITTime,
                                    currentQps, stop.cpuPercent ) );

        this.start = Monitor.start();
    }

    @Override
    public void onBegin( LoadGenerator loadGenerator )
    {
        begin = Monitor.start();
    }

    @Override
    public void onEnd( LoadGenerator loadGenerator )
    {
        Monitor.Stop end = begin.stop();
        long totalRequestCommitted = histogram.getTotalCount();
        long start = histogram.getStartTimeStamp();
        long timeInSeconds = TimeUnit.SECONDS.convert( histogram.getEndTimeStamp() - start, //
                                                       TimeUnit.MILLISECONDS );
        finalQps = totalRequestCommitted / timeInSeconds;
        LOGGER.info( String.format( "jit=%d ms, qps=%d, cpu=%.4f ms%n", end.deltaJITTime, finalQps, end.cpuPercent ) );
        HistogramSnapshot snapshot = new HistogramSnapshot( histogram, 20, "response time", //
                                                            "\u00B5s", //
                                                            TimeUnit.NANOSECONDS::toMicros );
        System.err.println( snapshot );

        CollectorInformations collectorInformations = new CollectorInformations( histogram );

        LoadResult loadResult = new LoadResult( serverInfo, collectorInformations );

        StringWriter stringWriter = new StringWriter();

        try
        {
            new ObjectMapper().writeValue( stringWriter, loadResult );
        } catch ( IOException e ) {
            LOGGER.info( "skip cannot write LoadResult as json", e );
        }

        LOGGER.info( "loadResult json: {}", stringWriter.toString() );

    }

    public long getCurrentQps()
    {
        return currentQps;
    }

    public long getFinalQps()
    {
        return finalQps;
    }

}
