CREATE FUNCTION check_ledger_balanced() RETURNS trigger AS $$
DECLARE
    total numeric;
    currencies integer;
BEGIN
    SELECT SUM(amount), COUNT(DISTINCT currency)
    INTO total, currencies
    FROM ledger_entries
    WHERE transaction_id = NEW.transaction_id;

    IF total <> 0 THEN
        RAISE EXCEPTION 'Ledger transaction % is not balanced', NEW.transaction_id;
    END IF;
    IF currencies > 1 THEN
        RAISE EXCEPTION 'Ledger transaction % mixes currencies', NEW.transaction_id;
    END IF;
    RETURN NULL;
END
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_ledger_balanced
AFTER INSERT ON ledger_entries
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION check_ledger_balanced();

CREATE FUNCTION check_entry_currency() RETURNS trigger AS $$
DECLARE
    account_currency char(3);
BEGIN
    SELECT currency INTO account_currency FROM accounts WHERE id = NEW.account_id;
    IF account_currency IS DISTINCT FROM NEW.currency THEN
        RAISE EXCEPTION 'Entry currency % does not match account % currency %',
            NEW.currency, NEW.account_id, account_currency;
    END IF;
    RETURN NEW;
END
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ledger_entry_currency
BEFORE INSERT ON ledger_entries
FOR EACH ROW EXECUTE FUNCTION check_entry_currency();

CREATE FUNCTION forbid_ledger_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Ledger is append-only: % on % is not allowed', TG_OP, TG_TABLE_NAME;
END
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ledger_entries_immutable
BEFORE UPDATE OR DELETE ON ledger_entries
FOR EACH ROW EXECUTE FUNCTION forbid_ledger_mutation();

CREATE TRIGGER trg_ledger_transactions_immutable
BEFORE UPDATE OR DELETE ON ledger_transactions
FOR EACH ROW EXECUTE FUNCTION forbid_ledger_mutation();
