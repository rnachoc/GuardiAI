-- The catalog's products. Prices are whole cents.
-- Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
create table products (
    sku         text primary key,
    name        text   not null,
    category    text   not null,
    price_cents bigint not null check (price_cents > 0),
    currency    text   not null default 'EUR' check (currency ~ '^[A-Z]{3}$'),
    created_at  timestamptz not null default now()
);

create index products_category_idx on products (category);
