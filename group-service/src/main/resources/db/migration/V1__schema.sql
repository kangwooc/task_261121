create table groups (
    id varchar(10) primary key check (id ~ '^[a-z0-9]{10}$'),
    name varchar not null,
    pricing varchar not null check (pricing in ('Free', 'Advanced', 'Premium')),
    registered_at timestamptz not null
);

create table group_members (
    member_id varchar(10) primary key check (member_id ~ '^[a-z0-9]{10}$'),
    group_id varchar(10) not null references groups (id),
    permission varchar not null check (permission in ('Viewer', 'Editor', 'Admin')),
    joined_at timestamptz not null
);

create index group_members_group_idx on group_members (group_id);
