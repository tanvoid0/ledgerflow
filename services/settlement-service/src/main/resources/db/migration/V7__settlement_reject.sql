create table settlement_reject (
    item_id       bigint      primary key references settlement_item (id),
    business_date date        not null,
    reason        text        not null,
    rejected_at   timestamptz not null default now()
);
