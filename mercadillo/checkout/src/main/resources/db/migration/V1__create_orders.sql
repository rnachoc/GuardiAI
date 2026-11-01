-- Placed orders and their lines, at the price paid.
-- Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
create table orders (
    id          uuid primary key,
    cart_id     text        not null,
    total_cents bigint      not null check (total_cents > 0),
    currency    text        not null check (currency ~ '^[A-Z]{3}$'),
    charge_id   text        not null,
    placed_at   timestamptz not null
);

create table order_lines (
    order_id         uuid    not null references orders (id) on delete cascade,
    sku              text    not null,
    quantity         integer not null check (quantity > 0),
    unit_price_cents bigint  not null check (unit_price_cents > 0),
    primary key (order_id, sku)
);
