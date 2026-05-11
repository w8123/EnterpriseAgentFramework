from django.db import migrations


class Migration(migrations.Migration):

    dependencies = [
        ('application', '0008_chat_ip_address_chat_source_chatrecord_ip_address_and_more'),
        ('system_manage', '0005_resourcemapping'),
    ]

    operations = [
        migrations.RunSQL(
            "DELETE FROM application_knowledge_mapping;",
            reverse_sql=migrations.RunSQL.noop
        ),
    ]
