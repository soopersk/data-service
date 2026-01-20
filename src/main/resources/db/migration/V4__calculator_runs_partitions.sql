-- Function to create daily partitions for next 60 days
CREATE OR REPLACE FUNCTION create_calculator_run_partitions()
RETURNS void AS $$
DECLARE
    start_date DATE;
    end_date DATE;
    partition_date DATE;
    partition_name TEXT;
    partition_exists BOOLEAN;
BEGIN
    -- Create partitions from yesterday to 60 days in future
    start_date := CURRENT_DATE - INTERVAL '1 day';
    end_date := CURRENT_DATE + INTERVAL '60 days';

    partition_date := start_date;

    WHILE partition_date <= end_date LOOP
        partition_name := 'calculator_runs_' || TO_CHAR(partition_date, 'YYYY_MM_DD');

        -- Check if partition exists
        SELECT EXISTS (
            SELECT 1 FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE c.relname = partition_name
            AND n.nspname = 'public'
        ) INTO partition_exists;

        IF NOT partition_exists THEN
            EXECUTE format(
                'CREATE TABLE IF NOT EXISTS %I PARTITION OF calculator_runs
                FOR VALUES FROM (%L) TO (%L)',
                partition_name,
                partition_date,
                partition_date + INTERVAL '1 day'
            );

            RAISE NOTICE 'Created partition: %', partition_name;
        END IF;

        partition_date := partition_date + INTERVAL '1 day';
    END LOOP;

    RAISE NOTICE 'Partition creation completed';
END;
$$ LANGUAGE plpgsql;

-- Function to drop old partitions (older than retention period)
CREATE OR REPLACE FUNCTION drop_old_calculator_run_partitions()
RETURNS void AS $$
DECLARE
    partition_record RECORD;
    cutoff_date DATE;
    daily_retention_days INTEGER := 7;      -- Keep DAILY runs for 7 days
    monthly_retention_days INTEGER := 395;  -- Keep MONTHLY runs for ~13 months
BEGIN
    -- Drop partitions older than the longest retention period
    cutoff_date := CURRENT_DATE - INTERVAL '395 days';

    FOR partition_record IN
        SELECT
            c.relname AS partition_name,
            pg_get_expr(c.relpartbound, c.oid) AS partition_bound
        FROM pg_class c
        JOIN pg_inherits i ON c.oid = i.inhrelid
        JOIN pg_class p ON i.inhparent = p.oid
        WHERE p.relname = 'calculator_runs'
        AND c.relname LIKE 'calculator_runs_%'
    LOOP
        -- Extract date from partition name (format: calculator_runs_YYYY_MM_DD)
        DECLARE
            partition_date DATE;
            date_string TEXT;
        BEGIN
            date_string := SUBSTRING(partition_record.partition_name FROM 'calculator_runs_(.*)');
            partition_date := TO_DATE(REPLACE(date_string, '_', '-'), 'YYYY-MM-DD');

            IF partition_date < cutoff_date THEN
                EXECUTE format('DROP TABLE IF EXISTS %I', partition_record.partition_name);
                RAISE NOTICE 'Dropped old partition: %', partition_record.partition_name;
            END IF;
        EXCEPTION
            WHEN OTHERS THEN
                RAISE WARNING 'Failed to process partition %: %',
                    partition_record.partition_name, SQLERRM;
        END;
    END LOOP;

    RAISE NOTICE 'Old partition cleanup completed';
END;
$$ LANGUAGE plpgsql;

-- Function to get partition statistics
CREATE OR REPLACE FUNCTION get_partition_statistics()
RETURNS TABLE (
    partition_name TEXT,
    partition_date DATE,
    row_count BIGINT,
    total_size TEXT,
    daily_runs BIGINT,
    monthly_runs BIGINT
) AS $$
BEGIN
    RETURN QUERY
    SELECT
        c.relname::TEXT,
        TO_DATE(REPLACE(SUBSTRING(c.relname FROM 'calculator_runs_(.*)'), '_', '-'), 'YYYY-MM-DD'),
        pg_class_size.reltuples::BIGINT,
        pg_size_pretty(pg_total_relation_size(c.oid)),
        (SELECT COUNT(*) FROM ONLY calculator_runs p
         WHERE p.tableoid = c.oid AND p.frequency = 'DAILY')::BIGINT,
        (SELECT COUNT(*) FROM ONLY calculator_runs p
         WHERE p.tableoid = c.oid AND p.frequency = 'MONTHLY')::BIGINT
    FROM pg_class c
    JOIN pg_inherits i ON c.oid = i.inhrelid
    JOIN pg_class parent ON i.inhparent = parent.oid
    LEFT JOIN LATERAL (
        SELECT reltuples FROM pg_class WHERE oid = c.oid
    ) pg_class_size ON true
    WHERE parent.relname = 'calculator_runs'
    AND c.relname LIKE 'calculator_runs_%'
    ORDER BY c.relname DESC;
END;
$$ LANGUAGE plpgsql;

-- ================================================================
-- AUTOMATED PARTITION MAINTENANCE
-- ================================================================

-- Create initial partitions
SELECT create_calculator_run_partitions();

-- Schedule automatic partition creation (via pg_cron if available)
-- Otherwise, call from application scheduler
COMMENT ON FUNCTION create_calculator_run_partitions() IS
'Call daily to create partitions for next 60 days';

COMMENT ON FUNCTION drop_old_calculator_run_partitions() IS
'Call weekly to drop partitions older than retention period';