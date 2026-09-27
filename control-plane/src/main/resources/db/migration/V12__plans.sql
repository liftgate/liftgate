alter table organizations alter column plan set default 'default';

update organizations set plan = 'default' where plan = 'free';
