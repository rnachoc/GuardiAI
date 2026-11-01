-- Stock levels and the stock held for each order.
-- Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
create table stock (
    sku       text primary key,
    available integer not null check (available >= 0)
);

-- One row per order that holds stock, so that asking twice for the same order holds it once.
create table reservation_orders (
    order_id    text primary key,
    reserved_at timestamptz not null default now()
);

create table reservation_lines (
    order_id text    not null references reservation_orders (order_id) on delete cascade,
    sku      text    not null references stock (sku),
    quantity integer not null check (quantity > 0),
    primary key (order_id, sku)
);

-- Plenty of every product, so that steady load runs for weeks without selling out.
insert into stock (sku, available)
select 'SKU-' || lpad(n::text, 4, '0'), 10000000
from generate_series(1, 40) as n;
