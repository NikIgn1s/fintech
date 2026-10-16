package dev.finflow.account.api;

import dev.finflow.account.domain.Account;
import java.math.BigDecimal;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper
public interface AccountMapper {

    @Mapping(target = "balance", source = "balance")
    AccountResponse toResponse(Account account, BigDecimal balance);
}
