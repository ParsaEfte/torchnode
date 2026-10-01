import io.github.gavinruff007.torchnode.analysis.NetworkAnalytics;
import java.time.Instant;

/** Bounded, read-only analytics smoke/latency utility for a copied database. */
public class ValidateNetworkAnalytics {
    public static void main(String[] args) throws Exception {
        if(args.length!=3 && !(args.length==2 && "all".equals(args[1])))
            throw new IllegalArgumentException("database-copy start-UTC end-UTC or database-copy all required");
        var scope=args.length==2?NetworkAnalytics.Scope.allAvailable():
                new NetworkAnalytics.Scope(Instant.parse(args[1]),Instant.parse(args[2]));
        long before=System.nanoTime();
        var report=new NetworkAnalytics(args[0]).measure(scope);
        long elapsed=System.nanoTime()-before;
        System.out.println("window="+report.scope()+" latency_ms="+elapsed/1_000_000.0);
        System.out.println("excluded_untimed_runs="+report.excludedUntimedRuns()+
                " excluded_untimed_endpoint_attempts="+report.excludedUntimedEndpointAttempts()+
                " excluded_untimed_rpc_responses="+report.excludedUntimedRpcResponses());
        for(var metric:report.metrics())System.out.println(metric.id()+" denominator="+metric.denominator()+
                " unknown="+metric.unknown()+" bucket_count="+metric.buckets().size()+
                " first_buckets="+metric.buckets().stream().limit(8).toList());
        System.out.println("snapshot="+new NetworkAnalytics(args[0]).snapshot());
    }
}
