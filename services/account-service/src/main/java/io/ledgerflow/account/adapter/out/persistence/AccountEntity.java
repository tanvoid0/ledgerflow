package io.ledgerflow.account.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Mirrors the accounts table. Mutable with a no-arg constructor because Hibernate
 * needs both, which is exactly why the domain record is a separate type.
 */
@Entity
@Table(name = "accounts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class AccountEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @OneToMany(mappedBy = "account", fetch = FetchType.EAGER)
    private List<WalletEntity> wallets = new ArrayList<>();
}
