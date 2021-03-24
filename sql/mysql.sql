create table if not exists blog_user(
    id bigint not null auto_increment,
    user_name varchar(255),
    user_password varchar(255),
    phone_number varchar(255),
    create_time datetime,
    update_time datetime,
    primary key (id)
)engine=MyISAM

create table if not exists blog_user_token(
    id bigint not null auto_increment,
    user_name varchar(255),
    auth_token varchar(255),
    create_time datetime,
    primary key (id)
)engine=MyISAM



// create_time没有默认值
// 当前修改方案是：直接将create_time的字段结构换成timestamp
//  alter table blog_user change update_time update_time timestamp default now();
// PS: timestamp是4字节，datatime是8字节，timestamp用于经常更换时间

