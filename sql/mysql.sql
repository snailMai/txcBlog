create table if not exists blog_user(
    id bigint not null auto_increment,
    user_id varchar(255) NOT NULL,
    user_name varchar(255) NOT NULL,
    user_password varchar(255) NOT NULL,
    phone_number varchar(255) NOT NULL,
    create_time datetime,
    update_time datetime,
    primary key (id),
    UNIQUE KEY(user_id)
)engine=InnoDB DEFAULT CHARSET=utf8

create table if not exists blog_user_token(
    id bigint auto_increment NOT NULL,
    user_id varchar(255) NOT NULL,
    auth_token varchar(255),
    startTimestamp int,
    endTimestamp int,
    primary key (id)
)engine=InnoDB DEFAULT CHARSET=utf8



// create_time没有默认值
// 当前修改方案是：直接将create_time的字段结构换成timestamp
//  alter table blog_user change update_time update_time timestamp default now();
// PS: timestamp是4字节，datatime是8字节，timestamp用于经常更换时间

