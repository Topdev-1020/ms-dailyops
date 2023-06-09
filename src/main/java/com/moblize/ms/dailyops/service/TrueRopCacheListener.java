package com.moblize.ms.dailyops.service;

import com.moblize.ms.dailyops.domain.MongoWell;
import com.moblize.ms.dailyops.repository.mongo.mob.MongoWellRepository;
import lombok.extern.slf4j.Slf4j;
import org.infinispan.client.hotrod.annotation.ClientCacheEntryCreated;
import org.infinispan.client.hotrod.annotation.ClientCacheEntryModified;
import org.infinispan.client.hotrod.annotation.ClientListener;
import org.infinispan.client.hotrod.event.ClientCacheEntryCreatedEvent;
import org.infinispan.client.hotrod.event.ClientCacheEntryModifiedEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@ClientListener
@Slf4j
@Component
public class TrueRopCacheListener {
    @Autowired
    private MongoWellRepository mongoWellRepository;
    @Autowired
    private TargetWindowDPVAService targetWindowDPVAService;
    @Autowired
    private RestClientService restClientService;
    @Autowired
    private NotifyDPVAService notifyDPVAService;
    @Value("${CODE}")
    private String customer;
    @ClientCacheEntryCreated
    public void entryCreated(ClientCacheEntryCreatedEvent<String> event) {
        updateData(event.getKey());
    }
    @ClientCacheEntryModified
    public void entryModified(ClientCacheEntryModifiedEvent<String> event) {
        updateData(event.getKey());
    }

    public void updateData(String key) {
        String wellUid = key;
        MongoWell mongoWell = mongoWellRepository.findByUid(key);
        log.debug("processWell {}, {}", wellUid, customer);
        if(mongoWell != null && mongoWell.getCustomer() != null && mongoWell.getCustomer().equalsIgnoreCase(customer)){
            notifyDPVAService.notifyDPVAJob(targetWindowDPVAService.getTargetWindowDetail(mongoWell.getUid()), mongoWell.getStatusWell());
            restClientService.processWell(mongoWell);
        } else {
            log.error("Update Data not a valid well {} for customer {}", wellUid, customer);
        }
    }


}
