package com.company.erp.warehouse;

import com.company.erp.common.query.SearchSpecs;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface WarehouseEntryRepository extends JpaRepository<WarehouseEntry, UUID>, JpaSpecificationExecutor<WarehouseEntry> {

    /**
     * The log, newest first. {@code open} = null all, true only given-out entries with units still to settle,
     * false only given-out entries that are fully settled. search matches product name/SKU or the note.
     */
    default Page<WarehouseEntry> search(Collection<UUID> branchIds, WarehouseEntryType type, Boolean open, String search,
                                        Instant from, Instant to, Pageable pageable) {
        Specification<WarehouseEntry> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            p.add(SearchSpecs.idIn(root.get("branch").get("id"), branchIds, cb));
            if (type != null) {
                p.add(cb.equal(root.get("entryType"), type));
            }
            if (open != null) {
                p.add(cb.equal(root.get("entryType"), WarehouseEntryType.ISSUE));
                p.add(open ? cb.greaterThan(root.<Integer>get("unsettledQuantity"), 0)
                        : cb.equal(root.<Integer>get("unsettledQuantity"), 0));
            }
            if (SearchSpecs.hasText(search)) {
                String term = search.trim();
                Join<Object, Object> product = root.join("product");
                p.add(cb.or(
                        SearchSpecs.containsIgnoreCase(product.<String>get("name"), term, cb),
                        SearchSpecs.containsIgnoreCase(product.<String>get("sku"), term, cb),
                        SearchSpecs.containsIgnoreCase(root.<String>get("note"), term, cb)));
            }
            if (from != null) {
                p.add(cb.greaterThanOrEqualTo(root.<Instant>get("createdAt"), from));
            }
            if (to != null) {
                p.add(cb.lessThan(root.<Instant>get("createdAt"), to));
            }
            return cb.and(p.toArray(new Predicate[0]));
        };
        return findAll(spec, SearchSpecs.withDefaultSort(pageable, Sort.by(Sort.Direction.DESC, "createdAt")));
    }
}
