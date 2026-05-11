select application_chat.*, application_chat.asker::json AS asker, application_chat.source::json AS source
from application_chat application_chat
    ${default_queryset}