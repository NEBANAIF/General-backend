-- Optional name of the person the warehouse gave the stock to (written when stock is given out; never required).
ALTER TABLE warehouse_entries ADD COLUMN recipient_name VARCHAR(200);
