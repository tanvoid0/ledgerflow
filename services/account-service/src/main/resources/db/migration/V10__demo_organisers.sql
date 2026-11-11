-- The six demo-event organisers (ticketing V3__seed), as payout accounts.
-- Account id is deterministic from the organiser name: UUID.nameUUIDFromBytes("organiser:" + name),
-- the same map settlement's MerchantPayoutListener uses to find the wallet. No opening balance:
-- an organiser earns from payouts, it is never granted a spending balance, so there is no journal
-- entry here (balance_minor defaults to 0). TREASURY (the settlement wallet) is what credits them.
INSERT INTO accounts (id, name) VALUES
  ('ac40be4f-7b83-3793-89dc-85eb7fd35ca8', 'org-wildfire'),
  ('6eb8c30d-3248-3301-b8e7-0df93bc18bab', 'org-laughtrack'),
  ('d65f5cc0-12a6-3b45-b246-2acc2881e7e8', 'org-hamlet'),
  ('1651bd79-81ca-3f75-ac93-5a076a234fe4', 'org-sixnations'),
  ('55bc9f95-8178-3379-b389-1a14dc3cbb6f', 'org-indienights'),
  ('11cbc430-c43b-3e30-a004-8c0bf89e6f32', 'org-tempest');

INSERT INTO wallets (id, account_id, label) VALUES
  ('3838889a-5e19-40da-a220-563a903857eb', 'ac40be4f-7b83-3793-89dc-85eb7fd35ca8', 'MAIN'),
  ('cd7f042d-f839-43d0-bd35-787f3454e80d', '6eb8c30d-3248-3301-b8e7-0df93bc18bab', 'MAIN'),
  ('900edd41-1f7d-43dd-ad09-9269c44314b9', 'd65f5cc0-12a6-3b45-b246-2acc2881e7e8', 'MAIN'),
  ('078ceebf-9023-4084-a30f-cb5edd0e087f', '1651bd79-81ca-3f75-ac93-5a076a234fe4', 'MAIN'),
  ('f63c7cec-8ad2-4302-a34b-ad391dcbcf56', '55bc9f95-8178-3379-b389-1a14dc3cbb6f', 'MAIN'),
  ('2f1e7344-2614-486b-97a9-38ef77603dbd', '11cbc430-c43b-3e30-a004-8c0bf89e6f32', 'MAIN');
