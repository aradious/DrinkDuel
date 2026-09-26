package com.drinkduel.room;

/** Internal identity binding; never serialize domain identities to clients. */
public sealed interface PlayerIdentity {
    record Guest(String fingerprint) implements PlayerIdentity {
        public Guest {
            if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}"))
                throw new DomainException(DomainException.Code.INVALID_INPUT);
        }
        @Override public String toString() { return "Guest[redacted]"; }
    }

    /** The caller must supply a verified Google subject; OAuth is outside the domain. */
    record Google(String subject) implements PlayerIdentity {
        public Google { DomainException.requireText(subject); }
        @Override public String toString() { return "Google[redacted]"; }
    }
}
