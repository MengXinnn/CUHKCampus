create table users (
    id uuid primary key,
    email varchar(320) not null unique,
    password_hash varchar(255) not null,
    display_name varchar(255) not null,
    timezone varchar(255) not null default 'Asia/Shanghai',
    preferences jsonb not null default '{}'
);

create table external_connections (
    id uuid primary key,
    user_id uuid not null references users(id) on delete cascade,
    provider varchar(255) not null,
    account_identifier varchar(255),
    credentials jsonb not null default '{}',
    profile_path varchar(255),
    status varchar(255) not null,
    last_synced_at timestamptz,
    unique(user_id, provider)
);

create table courses (
    id uuid primary key,
    user_id uuid not null references users(id) on delete cascade,
    external_id varchar(255),
    code varchar(255),
    name varchar(255) not null,
    custom_name varchar(255),
    term varchar(255),
    teacher varchar(255),
    color varchar(255) not null default '#D6E0CF',
    archived boolean not null default false,
    note text,
    last_synced_at timestamptz,
    unique(user_id, external_id)
);

create table content_nodes (
    id uuid primary key,
    course_id uuid not null references courses(id) on delete cascade,
    parent_id uuid references content_nodes(id) on delete cascade,
    external_id varchar(255),
    node_type varchar(255) not null,
    title varchar(255) not null,
    body_html text,
    body_text text,
    due_at timestamptz,
    source_url text,
    position integer not null default 0,
    unique(course_id, external_id)
);

create table announcements (
    id uuid primary key,
    course_id uuid not null references courses(id) on delete cascade,
    external_id varchar(255),
    title varchar(255) not null,
    body_html text,
    body_text text,
    published_at timestamptz,
    is_read boolean not null default false,
    unique(course_id, external_id)
);

create table mail_folders (
    id uuid primary key,
    connection_id uuid not null references external_connections(id) on delete cascade,
    external_id varchar(255) not null,
    display_name varchar(255) not null,
    selected boolean not null default false,
    delta_link text,
    last_synced_at timestamptz,
    unique(connection_id, external_id)
);

create table mail_messages (
    id uuid primary key,
    user_id uuid not null references users(id) on delete cascade,
    folder_id uuid not null references mail_folders(id) on delete cascade,
    course_id uuid references courses(id) on delete set null,
    external_id varchar(255) not null,
    subject varchar(255),
    sender jsonb,
    received_at timestamptz,
    body_html text,
    body_text text,
    web_link text,
    category varchar(255) not null default 'GENERAL',
    importance varchar(255) not null default 'NORMAL',
    is_read boolean not null default false,
    summary text,
    unique(user_id, external_id)
);

create table documents (
    id uuid primary key,
    user_id uuid not null references users(id) on delete cascade,
    course_id uuid references courses(id) on delete set null,
    content_node_id uuid references content_nodes(id) on delete set null,
    mail_message_id uuid references mail_messages(id) on delete set null,
    external_id varchar(255),
    display_name varchar(255) not null,
    mime_type varchar(255) not null,
    byte_size bigint not null,
    sha256 varchar(255) not null,
    storage_key varchar(255) not null,
    origin varchar(255) not null
);

create table course_notes (
    id uuid primary key,
    user_id uuid not null references users(id) on delete cascade,
    course_id uuid not null references courses(id) on delete cascade,
    document_id uuid references documents(id) on delete set null,
    title varchar(255) not null,
    markdown text
);

create table tasks (
    id uuid primary key,
    user_id uuid not null references users(id) on delete cascade,
    course_id uuid references courses(id) on delete set null,
    task_type varchar(255) not null,
    title varchar(255) not null,
    description text,
    status varchar(255) not null default 'TODO',
    priority varchar(255) not null default 'NORMAL',
    due_at timestamptz,
    estimated_minutes integer,
    organizer varchar(255),
    category varchar(255),
    starts_at timestamptz,
    ends_at timestamptz,
    location varchar(255),
    registration_url text,
    starred boolean not null default false,
    source_type varchar(255),
    source_id uuid
);

create table course_meeting_rules (
    id uuid primary key,
    course_id uuid not null references courses(id) on delete cascade,
    weekday integer not null,
    starts_time time not null,
    ends_time time not null,
    valid_from date not null,
    valid_to date not null,
    location varchar(255)
);

create table calendar_events (
    id uuid primary key,
    user_id uuid not null references users(id) on delete cascade,
    course_id uuid references courses(id) on delete set null,
    task_id uuid references tasks(id) on delete set null,
    meeting_rule_id uuid references course_meeting_rules(id) on delete cascade,
    event_type varchar(255) not null,
    title varchar(255) not null,
    starts_at timestamptz not null,
    ends_at timestamptz not null,
    all_day boolean not null default false,
    location varchar(255)
);

create table conversations (
    id uuid primary key,
    user_id uuid not null references users(id) on delete cascade,
    title text not null,
    created_at timestamptz not null default now(),
    last_message_at timestamptz not null default now()
);

create table chat_messages (
    id uuid primary key,
    conversation_id uuid not null references conversations(id) on delete cascade,
    role varchar(255) not null,
    content text not null,
    context_refs jsonb not null default '[]',
    tool_calls jsonb not null default '[]',
    created_at timestamptz not null default now()
);

create table briefings (
    id uuid primary key,
    user_id uuid not null references users(id) on delete cascade,
    local_date date not null,
    content_markdown text not null,
    generated_at timestamptz not null default now(),
    unique(user_id, local_date)
);

create index courses_user on courses(user_id);
create index documents_user on documents(user_id);
create index tasks_user on tasks(user_id);
create index calendar_user_time on calendar_events(user_id, starts_at);
create index conversations_user_time on conversations(user_id, last_message_at desc);
create index chat_messages_conversation_time on chat_messages(conversation_id, created_at, id);
