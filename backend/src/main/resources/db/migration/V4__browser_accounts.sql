create table browser_accounts (
    user_id uuid not null references users(id) on delete cascade,
    provider varchar(32) not null,
    encrypted text not null,
    primary key(user_id,provider)
);

update users set preferences=preferences-'outlookClientId';
