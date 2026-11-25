-- money a merchant owes back, taken out of their next batch: a clawback the wallet could not cover,
-- or what is left when a debt is bigger than that day's net (from_date = the batch that left it over)
create table settlement_carry (
    source       text    primary key,
    merchant_id  text    not null,
    currency     char(3) not null,
    amount_minor bigint  not null check (amount_minor > 0),
    from_date    date,
    applied_on   date
);
