package io.ledgerflow.settlement.batch;

/** A row that is wrong rather than unlucky: no retry will fix it, only a skip. */
class UnsettleableItemException extends RuntimeException {

    UnsettleableItemException(String message) {
        super(message);
    }
}
