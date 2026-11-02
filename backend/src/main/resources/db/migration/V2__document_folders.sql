create table document_folders (
    id uuid primary key,
    user_id uuid not null references users(id) on delete cascade,
    parent_id uuid references document_folders(id) on delete cascade,
    name varchar(255) not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);
alter table documents add column folder_id uuid references document_folders(id) on delete cascade;
alter table documents add column created_at timestamptz not null default now();
alter table documents add column updated_at timestamptz not null default now();
create index document_folders_parent on document_folders(user_id,parent_id);
create index documents_folder on documents(user_id,folder_id);
