create table members (
    id varchar(10) primary key,
    name varchar collate "ko-x-icu" not null, -- ICU Korean: dictionary order; ORDER BY and keyset compare share it
    email varchar not null unique,
    address varchar not null,
    registered_at timestamptz not null
);

create index members_name_sort_idx on members (name, registered_at, id);
