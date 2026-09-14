package io.ledgerflow.account.adapter.out.persistence;

import io.ledgerflow.account.domain.model.Account;
import io.ledgerflow.account.domain.model.Wallet;
import org.mapstruct.Mapper;

/** Entity in, domain record out. MapStruct writes the implementation at compile time. */
@Mapper
interface AccountMapper {

    Account toDomain(AccountEntity entity);

    Wallet toDomain(WalletEntity entity);
}
