package dev.finflow.importing.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class DescriptionNormalizer {

    private static final int MAX_MERCHANT_LENGTH = 255;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern STORE_TOKEN = Pattern.compile("^[#№]?\\d.*|^[#№].*");
    private static final Set<String> PREFIXES = Set.of("PAYMENT", "PURCHASE", "POKUPKA", "OPLATA", "OOO", "ООО",
            "ИП", "IP", "ОПЛАТА", "ПОКУПКА");
    private static final Set<String> LOCATION_WORDS = Set.of("RUS", "RU", "RUSSIA", "MOSCOW", "MOSKVA", "SPB",
            "KAZAN", "EKATERINBURG", "NOVOSIBIRSK", "SAMARA", "UFA", "PERM", "МОСКВА", "РОССИЯ", "РФ", "СПБ",
            "КАЗАНЬ", "SAINT-PETERSBURG", "ST.PETERSBURG");

    public String merchant(String rawDescription) {
        String upper = rawDescription.trim().toUpperCase(Locale.ROOT);
        String beforeStar = upper.contains("*") ? upper.substring(0, upper.indexOf('*')) : upper;
        List<String> tokens = new ArrayList<>(List.of(WHITESPACE.split(beforeStar.trim())));
        while (tokens.size() > 1 && PREFIXES.contains(tokens.get(0))) {
            tokens.remove(0);
        }
        List<String> kept = new ArrayList<>();
        for (String token : tokens) {
            if (!kept.isEmpty() && (STORE_TOKEN.matcher(token).matches() || LOCATION_WORDS.contains(token))) {
                break;
            }
            kept.add(token);
        }
        String merchant = String.join(" ", kept).trim();
        if (merchant.isEmpty()) {
            merchant = upper;
        }
        return merchant.length() <= MAX_MERCHANT_LENGTH ? merchant : merchant.substring(0, MAX_MERCHANT_LENGTH);
    }
}
