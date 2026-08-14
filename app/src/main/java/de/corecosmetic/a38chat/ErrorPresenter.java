package de.corecosmetic.a38chat;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

final class ErrorPresenter {
    private static final Pattern AUTHORIZATION_VALUE = Pattern.compile(
            "(?i)(authorization[\"']?\\s*[:=]\\s*[\"']?)(?:(?:bearer|basic)\\s+)?([^\\s,;\"']+)"
    );
    private static final Pattern BEARER_VALUE = Pattern.compile(
            "(?i)(\\bbearer\\s+)([^\\s,;\"']+)"
    );
    private static final Pattern SENSITIVE_ASSIGNMENT = Pattern.compile(
            "(?i)(token|password|passwd|secret)([\"']?\\s*[:=]\\s*[\"']?)([^\\s,;\"']+)"
    );

    private ErrorPresenter() {
    }

    static String message(String language, Exception error, boolean debugEnabled) {
        String friendly = friendly(language, error);
        if (!debugEnabled) {
            return friendly;
        }
        return friendly + "\n\nDebug: " + technicalDetails(error);
    }

    private static String friendly(String language, Exception error) {
        if (hasCause(error, UnknownHostException.class)
                || hasCause(error, NoRouteToHostException.class)) {
            return translated(
                    language,
                    "No internet connection.",
                    "Keine Internetverbindung.",
                    "Pas de connexion Internet.",
                    "Нет подключения к интернету.",
                    "Немає підключення до інтернету.",
                    "Nessuna connessione Internet."
            );
        }
        if (hasCause(error, SocketTimeoutException.class)) {
            return translated(
                    language,
                    "The connection timed out. Please try again.",
                    "Die Verbindung hat zu lange gedauert. Bitte versuche es erneut.",
                    "La connexion a expiré. Réessayez.",
                    "Время ожидания соединения истекло. Повторите попытку.",
                    "Час очікування з’єднання минув. Спробуйте ще раз.",
                    "La connessione è scaduta. Riprova."
            );
        }
        if (hasCause(error, ConnectException.class)) {
            return translated(
                    language,
                    "Could not connect to the chat service.",
                    "Die Verbindung zum Chatdienst konnte nicht hergestellt werden.",
                    "Impossible de se connecter au service de chat.",
                    "Не удалось подключиться к сервису чата.",
                    "Не вдалося підключитися до сервісу чату.",
                    "Impossibile connettersi al servizio chat."
            );
        }
        if (error instanceof ChatApi.ApiException) {
            int status = ((ChatApi.ApiException) error).statusCode;
            if (status == 429) {
                return translated(
                        language,
                        "Too many requests. Please try again later.",
                        "Zu viele Anfragen. Bitte versuche es später erneut.",
                        "Trop de requêtes. Réessayez plus tard.",
                        "Слишком много запросов. Повторите попытку позже.",
                        "Забагато запитів. Спробуйте пізніше.",
                        "Troppe richieste. Riprova più tardi."
                );
            }
            if (status >= 500) {
                return translated(
                        language,
                        "The chat service is currently unavailable.",
                        "Der Chatdienst ist derzeit nicht erreichbar.",
                        "Le service de chat est actuellement indisponible.",
                        "Сервис чата сейчас недоступен.",
                        "Сервіс чату зараз недоступний.",
                        "Il servizio chat non è al momento disponibile."
                );
            }
        }
        return translated(
                language,
                "The action could not be completed.",
                "Die Aktion konnte nicht abgeschlossen werden.",
                "L’action n’a pas pu être effectuée.",
                "Не удалось выполнить действие.",
                "Не вдалося виконати дію.",
                "Impossibile completare l’azione."
        );
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 5; depth++) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String technicalDetails(Exception error) {
        if (error == null) {
            return "Unknown error";
        }
        StringBuilder details = new StringBuilder(error.getClass().getSimpleName());
        if (error instanceof ChatApi.ApiException) {
            details.append(" (HTTP ").append(((ChatApi.ApiException) error).statusCode).append(')');
        }
        String raw = error.getMessage();
        if (raw != null && !raw.trim().isEmpty()) {
            details.append(": ").append(limit(redact(raw.trim()), 500));
        }
        Throwable cause = error.getCause();
        if (cause != null && cause != error) {
            details.append("\nCause: ").append(cause.getClass().getSimpleName());
            String causeMessage = cause.getMessage();
            if (causeMessage != null && !causeMessage.trim().isEmpty()) {
                details.append(": ").append(limit(redact(causeMessage.trim()), 300));
            }
        }
        return details.toString();
    }

    private static String limit(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum) + "…";
    }

    private static String redact(String value) {
        String withoutAuthorization = AUTHORIZATION_VALUE.matcher(value).replaceAll("$1[redacted]");
        String withoutBearer = BEARER_VALUE.matcher(withoutAuthorization).replaceAll("$1[redacted]");
        return SENSITIVE_ASSIGNMENT.matcher(withoutBearer).replaceAll("$1$2[redacted]");
    }

    private static String translated(
            String language,
            String english,
            String german,
            String french,
            String russian,
            String ukrainian,
            String italian
    ) {
        if ("de".equals(language)) return german;
        if ("fr".equals(language)) return french;
        if ("ru".equals(language)) return russian;
        if ("uk".equals(language)) return ukrainian;
        if ("it".equals(language)) return italian;
        return english;
    }
}
