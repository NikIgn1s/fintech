package dev.finflow.account.api;

import dev.finflow.account.domain.Account;
import java.util.List;
import org.mapstruct.Mapper;

@Mapper
public interface AccountMapper {

    AccountResponse toResponse(Account account);

    List<AccountResponse> toResponses(List<Account> accounts);
}
