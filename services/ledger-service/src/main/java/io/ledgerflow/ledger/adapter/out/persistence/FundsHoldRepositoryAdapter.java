package io.ledgerflow.ledger.adapter.out.persistence;

import io.ledgerflow.ledger.application.FundsHoldRepository;
import io.ledgerflow.ledger.domain.model.FundsHold;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
class FundsHoldRepositoryAdapter implements FundsHoldRepository {

    private final JpaFundsHoldRepository jpa;
    private final FundsHoldMapper mapper;

    @Override
    public List<FundsHold> saveAll(List<FundsHold> holds) {
        return jpa.saveAll(holds.stream().map(mapper::toEntity).toList())
                .stream().map(mapper::toDomain).toList();
    }
}
