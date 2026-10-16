package dev.finflow.transfer.api;

import java.util.Locale;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

@Component
public class TransferDirectionConverter implements Converter<String, TransferDirection> {

    @Override
    public TransferDirection convert(String source) {
        return TransferDirection.valueOf(source.trim().toUpperCase(Locale.ROOT));
    }
}
