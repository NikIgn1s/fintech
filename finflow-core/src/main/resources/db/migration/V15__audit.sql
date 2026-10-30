CREATE SEQUENCE revision_seq START WITH 2 INCREMENT BY 1;
CREATE TABLE revinfo (
    rev bigint PRIMARY KEY,
    revtstmp bigint NOT NULL,
    user_id uuid,
    ip varchar(64)
);

CREATE TABLE accounts_aud (
    id bigint NOT NULL, rev bigint NOT NULL REFERENCES revinfo(rev), revtype smallint,
    user_id uuid, type varchar(255), currency char(3), status varchar(255), created_at timestamptz,
    PRIMARY KEY(id,rev)
);
CREATE TABLE transfers_aud (
    id uuid NOT NULL, rev bigint NOT NULL REFERENCES revinfo(rev), revtype smallint,
    from_account_id bigint, to_account_id bigint, amount numeric(19,4), currency char(3),
    status varchar(255), description varchar(255), fraud_score numeric(5,4), hold_reason varchar(255),
    initiated_by uuid, created_at timestamptz, updated_at timestamptz,
    PRIMARY KEY(id,rev)
);
CREATE TABLE budgets_aud (
    id bigint NOT NULL, rev bigint NOT NULL REFERENCES revinfo(rev), revtype smallint,
    user_id uuid, category_id smallint, month date, currency char(3), amount_limit numeric(19,4),
    PRIMARY KEY(id,rev)
);
CREATE TABLE category_rules_aud (
    id bigint NOT NULL, rev bigint NOT NULL REFERENCES revinfo(rev), revtype smallint,
    user_id uuid, pattern varchar(255), category_id smallint, priority integer,
    PRIMARY KEY(id,rev)
);

INSERT INTO revinfo(rev,revtstmp) VALUES (1,(extract(epoch FROM clock_timestamp())*1000)::bigint);
INSERT INTO accounts_aud SELECT id,1,0,user_id,type,currency,status,created_at FROM accounts;
INSERT INTO transfers_aud
    SELECT id,1,0,from_account_id,to_account_id,amount,currency,status,description,fraud_score,hold_reason,
           initiated_by,created_at,updated_at FROM transfers;
INSERT INTO budgets_aud SELECT id,1,0,user_id,category_id,month,currency,amount_limit FROM budgets;
INSERT INTO category_rules_aud SELECT id,1,0,user_id,pattern,category_id,priority FROM category_rules;

CREATE TABLE audit_log (
    id bigserial PRIMARY KEY,
    user_id uuid NOT NULL,
    ip varchar(64),
    entity_type varchar(30) NOT NULL,
    entity_id varchar(64) NOT NULL,
    action varchar(30) NOT NULL,
    reason varchar(255),
    created_at timestamptz NOT NULL
);
CREATE INDEX idx_audit_log_entity ON audit_log(entity_type,entity_id,id);
CREATE INDEX idx_audit_log_actor ON audit_log(user_id,created_at DESC);

CREATE FUNCTION reject_audit_log_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only';
END;
$$;
CREATE TRIGGER trg_audit_log_immutable BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION reject_audit_log_mutation();
