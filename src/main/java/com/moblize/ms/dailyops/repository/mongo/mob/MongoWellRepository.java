package com.moblize.ms.dailyops.repository.mongo.mob;

import com.moblize.ms.dailyops.domain.MongoWell;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.stream.Stream;

@Repository
public interface MongoWellRepository extends MongoRepository<MongoWell, String> {
    public List<MongoWell> findAllByUidIn(List<String> iterable);

    public List<MongoWell> findAllByUidInAndStatusWell(List<String> iterable, String statusWell);
    public List<MongoWell> findAllByUidInAndStatusWellNotContains(List<String> iterable, String statusWell);

    public Stream<MongoWell> findAllByCustomer(String customer);

    public Stream<MongoWell> findAllByCustomerAndIsHidden(String customer, Boolean isHidden);

    MongoWell findByUid(String uid);

    MongoWell findFirstByUid(String uid);
}
