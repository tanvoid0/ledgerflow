package io.ledgerflow.ledger.adapter.out.persistence;

import io.ledgerflow.events.Money;
import io.ledgerflow.ledger.domain.model.FundsHold;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper
interface FundsHoldMapper {

    @Mapping(target = "amount", expression = "java(new Money(e.getAmountMinor(), e.getCurrency()))")
    FundsHold toDomain(FundsHoldEntity e);

    @Mapping(target = "amountMinor", source = "amount.minorUnits")
    @Mapping(target = "currency", source = "amount.currency")
    FundsHoldEntity toEntity(FundsHold hold);
}
