package io.github.gavinruff007.torchnode.topology;

import org.sqlite.ProgressHandler;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;

/** Bounds SQLite VM execution using monotonic time; independent of busy_timeout. */
public final class SqliteReadDeadline implements AutoCloseable {
    private final Connection connection;
    public SqliteReadDeadline(Connection connection,Duration budget) throws SQLException {
        if(budget==null || budget.isNegative() || budget.isZero() || budget.compareTo(Duration.ofSeconds(5))>0)
            throw new IllegalArgumentException("Read budget must be positive and at most five seconds");
        this.connection=connection;long deadline=System.nanoTime()+budget.toNanos();
        ProgressHandler.setHandler(connection,1000,new ProgressHandler(){
            @Override protected int progress(){return Thread.currentThread().isInterrupted() || System.nanoTime()-deadline>=0?1:0;}
        });
    }
    @Override public void close() throws SQLException {ProgressHandler.clearHandler(connection);}
}
