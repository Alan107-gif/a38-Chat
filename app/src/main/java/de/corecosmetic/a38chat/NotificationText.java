package de.corecosmetic.a38chat;

final class NotificationText {
    final String section;
    final String enabled;
    final String removeAfter;
    final String never;
    final String oneMinute;
    final String fiveMinutes;
    final String fifteenMinutes;
    final String oneHour;
    final String serviceTitle;
    final String serviceText;
    final String image;
    final String permissionDenied;

    private NotificationText(
            String section,
            String enabled,
            String removeAfter,
            String never,
            String oneMinute,
            String fiveMinutes,
            String fifteenMinutes,
            String oneHour,
            String serviceTitle,
            String serviceText,
            String image,
            String permissionDenied
    ) {
        this.section = section;
        this.enabled = enabled;
        this.removeAfter = removeAfter;
        this.never = never;
        this.oneMinute = oneMinute;
        this.fiveMinutes = fiveMinutes;
        this.fifteenMinutes = fifteenMinutes;
        this.oneHour = oneHour;
        this.serviceTitle = serviceTitle;
        this.serviceText = serviceText;
        this.image = image;
        this.permissionDenied = permissionDenied;
    }

    static NotificationText from(String code) {
        if ("en".equals(code)) {
            return new NotificationText("Notifications", "New messages", "Remove automatically", "Never", "1 min", "5 min", "15 min", "1 hour", "a38-Chat active", "Waiting for new messages", "Image", "Notification permission was not granted.");
        }
        if ("fr".equals(code)) {
            return new NotificationText("Notifications", "Nouveaux messages", "Supprimer automatiquement", "Jamais", "1 min", "5 min", "15 min", "1 heure", "a38-Chat actif", "En attente de nouveaux messages", "Image", "L’autorisation de notification n’a pas été accordée.");
        }
        if ("ru".equals(code)) {
            return new NotificationText("Уведомления", "Новые сообщения", "Удалять автоматически", "Никогда", "1 мин", "5 мин", "15 мин", "1 час", "a38-Chat активен", "Ожидание новых сообщений", "Изображение", "Разрешение на уведомления не предоставлено.");
        }
        if ("uk".equals(code)) {
            return new NotificationText("Сповіщення", "Нові повідомлення", "Видаляти автоматично", "Ніколи", "1 хв", "5 хв", "15 хв", "1 год", "a38-Chat активний", "Очікування нових повідомлень", "Зображення", "Дозвіл на сповіщення не надано.");
        }
        if ("it".equals(code)) {
            return new NotificationText("Notifiche", "Nuovi messaggi", "Rimuovi automaticamente", "Mai", "1 min", "5 min", "15 min", "1 ora", "a38-Chat attivo", "In attesa di nuovi messaggi", "Immagine", "Il permesso per le notifiche non è stato concesso.");
        }
        return new NotificationText("Benachrichtigungen", "Neue Nachrichten", "Automatisch entfernen", "Nie", "1 Min", "5 Min", "15 Min", "1 Std", "a38-Chat aktiv", "Wartet auf neue Nachrichten", "Bild", "Die Benachrichtigungsberechtigung wurde nicht erteilt.");
    }
}
