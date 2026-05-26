# UpTags MySQL Order Storage Migration

## What Changes

`uptags_player_data.data_json` now stores only the current player state:

- `ownedTags`
- `tagProgress`
- `customTitles`
- `equippedTagId`
- `equippedCustomTitleId`
- `titleCoinBalance`
- `challengeProgress`

Order recovery data is stored in two separate tables:

- `uptags_purchase_orders`
- `uptags_custom_title_orders`

On startup, UpTags reads legacy `purchaseOrders` and `customTitleOrders` from existing `data_json`, writes them to the order tables, then rewrites the player row without embedded order maps.

## Manual SQL Pre-Migration

The plugin creates these tables automatically. If you prefer to prepare them before starting the server, run:

```sql
CREATE TABLE IF NOT EXISTS uptags_purchase_orders (
    order_id VARCHAR(64) PRIMARY KEY,
    uuid VARCHAR(36) NOT NULL,
    status VARCHAR(32) NOT NULL,
    currency_type VARCHAR(32) NOT NULL,
    amount DOUBLE NOT NULL,
    target_id VARCHAR(128) NOT NULL,
    product_id VARCHAR(128) NOT NULL,
    failure_reason TEXT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    submitted_items TEXT NULL,
    compensated_items TEXT NULL
);

CREATE INDEX idx_uptags_purchase_orders_uuid_updated
    ON uptags_purchase_orders (uuid, updated_at);
CREATE INDEX idx_uptags_purchase_orders_status_updated
    ON uptags_purchase_orders (status, updated_at);

CREATE TABLE IF NOT EXISTS uptags_custom_title_orders (
    order_id VARCHAR(64) PRIMARY KEY,
    uuid VARCHAR(36) NOT NULL,
    status VARCHAR(32) NOT NULL,
    currency_type VARCHAR(32) NOT NULL,
    amount DOUBLE NOT NULL,
    title_id VARCHAR(128) NOT NULL,
    raw_text TEXT NOT NULL,
    preset_id VARCHAR(128) NOT NULL,
    group_id VARCHAR(128) NULL,
    failure_reason TEXT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    submitted_items TEXT NULL,
    compensated_items TEXT NULL,
    previous_equipped_tag_id VARCHAR(128) NULL,
    previous_equipped_custom_title_id VARCHAR(128) NULL
);

CREATE INDEX idx_uptags_custom_title_orders_uuid_updated
    ON uptags_custom_title_orders (uuid, updated_at);
CREATE INDEX idx_uptags_custom_title_orders_status_updated
    ON uptags_custom_title_orders (status, updated_at);
```

## Required Backup

Before upgrading, back up the existing player table:

```sql
CREATE TABLE uptags_player_data_backup_before_order_split
AS SELECT * FROM uptags_player_data;
```

## Retention Config

```yaml
storage:
  order-retention:
    completed-days: 7
    failed-days: 7
    refunded-days: 30
    max-per-player: 50
```

Recoverable purchase statuses are retained for crash recovery:

`PENDING`, `ITEMS_TAKEN`, `PAID`, `GRANTING`, `REFUND_PENDING`

Recoverable custom title statuses are retained for crash recovery:

`PENDING`, `REFUND_PENDING`

Terminal orders are cleaned by retention:

`GRANTED` or `COMPLETED`, `FAILED`, `REFUNDED`

## Rollback

1. Stop the server.
2. Restore the backup table:

```sql
DROP TABLE uptags_player_data;
RENAME TABLE uptags_player_data_backup_before_order_split TO uptags_player_data;
```

3. If you must remove the new order tables:

```sql
DROP TABLE IF EXISTS uptags_purchase_orders;
DROP TABLE IF EXISTS uptags_custom_title_orders;
```

Rollback restores the old embedded order JSON only if the backup was taken before the first upgraded startup.
