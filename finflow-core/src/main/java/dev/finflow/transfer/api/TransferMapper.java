package dev.finflow.transfer.api;

import dev.finflow.transfer.domain.Transfer;
import org.mapstruct.Mapper;

@Mapper
public interface TransferMapper {

    TransferResponse toResponse(Transfer transfer);
}
