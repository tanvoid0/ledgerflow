package io.ledgerflow.ledger.adapter.out.persistence;

import io.ledgerflow.ledger.application.FundsHoldRepository;
import io.ledgerflow.ledger.domain.model.FundsHold;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

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

    @Override
    public List<FundsHold> closeAll(UUID reference, FundsHold.Status to) {
        var open = jpa.findAllByReferenceAndStatus(reference, FundsHold.Status.HELD);
        open.forEach(e -> e.setStatus(to));   // managed entities: the caller's transaction flushes them
        return open.stream().map(mapper::toDomain).toList();
    }
}
