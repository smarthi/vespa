// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.athenz.client.zms.bindings;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * @author bjorncs
 * @author mortent
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class MembershipEntity {
    public final String memberName;
    public final boolean isMember;
    public final String expiration;

    @JsonCreator
    public MembershipEntity(@JsonProperty("memberName") String memberName,
                            @JsonProperty("isMember") boolean isMember,
                            @JsonProperty("expiration") String expiration) {
        this.memberName = memberName;
        this.isMember = isMember;
        this.expiration = expiration;
    }

    @JsonGetter("memberName")
    public String memberName() {
        return memberName;
    }

    @JsonGetter("isMember")
    public boolean isMember() {
        return isMember;
    }

    @JsonGetter("expiration")
    public String expiration() {
        return expiration;
    }

    public static class RoleMembershipEntity extends MembershipEntity {
        public final String roleName;

        @JsonCreator
        public RoleMembershipEntity(@JsonProperty("memberName") String memberName,
                                    @JsonProperty("isMember") boolean isMember,
                                    @JsonProperty("roleName") String roleName,
                                    @JsonProperty("expiration") String expiration) {
            super(memberName, isMember, expiration);
            this.roleName = roleName;
        }

        @JsonGetter("roleName")
        public String roleName() {
            return roleName;
        }

    }

    public static class GroupMembershipEntity extends MembershipEntity {
        public final String groupName;

        @JsonCreator
        public GroupMembershipEntity(@JsonProperty("memberName") String memberName,
                                     @JsonProperty("isMember") boolean isMember,
                                     @JsonProperty("groupName") String groupName,
                                     @JsonProperty("expiration") String expiration) {
            super(memberName, isMember, expiration);
            this.groupName = groupName;
        }

        @JsonGetter("groupName")
        public String roleName() {
            return groupName;
        }
    }
}