-- File: src/main/resources/db/migration/V3__create_partition_functions.sql

CREATE OR REPLACE FUNCTION create_calculator_run_partitions()
RETURNS void AS $$
DECLARE
    start_date DATE;
    end_date DATE;
    partition_name TEXT;
BEGIN
    FOR i IN 0..30 LOOP
        start_date := CURRENT_DATE + (i || ' days')::INTERVAL;
        end_date := start_date + INTERVAL '1 day';
        partition_name := 'calculator_runs_' || to_char(start_date, 'YYYY_MM_DD');
        
        IF NOT EXISTS (
            SELECT 1 FROM pg_class WHERE relname = partition_name
        ) THEN
            EXECUTE format(
                'CREATE TABLE %I PARTITION OF calculator_runs 
                FOR VALUES FROM (%L) TO (%L)',
                partition_name, start_date, end_date
            );
            
            RAISE NOTICE 'Created partition %', partition_name;
        END IF;
    END LOOP;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION drop_old_calculator_run_partitions()
RETURNS void AS $$
DECLARE
    partition_record RECORD;
    retention_date DATE := CURRENT_DATE - INTERVAL '90 days';
BEGIN
    FOR partition_record IN
        SELECT tablename 
        FROM pg_tables 
        WHERE schemaname = 'public' 
        AND tablename LIKE 'calculator_runs_%'
        AND tablename < 'calculator_runs_' || to_char(retention_date, 'YYYY_MM_DD')
    LOOP
        EXECUTE format('DROP TABLE IF EXISTS %I', partition_record.tablename);
        RAISE NOTICE 'Dropped partition %', partition_record.tablename;
    END LOOP;
END;
$$ LANGUAGE plpgsql;