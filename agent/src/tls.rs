//! Shared TLS 1.3 material for the encrypted data paths (TCP+TLS and QUIC).
//!
//! This is a *benchmark* surface: the receiver mints a fresh self-signed certificate
//! per process and the sender accepts any certificate. That keeps the handshake
//! real (TLS 1.3, measured in the Gantt) without a PKI to provision — which is
//! exactly what a throughput comparator wants. It is deliberately NOT suitable
//! for anything but testing.

use std::sync::Arc;

use anyhow::Result;
use rustls::pki_types::{CertificateDer, PrivateKeyDer, ServerName, UnixTime};
use rustls::{ClientConfig, ServerConfig};

pub const ALPN: &[u8] = b"bwtest";

/// A self-signed cert + key for the receiver, in DER.
pub struct SelfSigned {
    pub cert: CertificateDer<'static>,
    pub key: PrivateKeyDer<'static>,
}

pub fn self_signed() -> Result<SelfSigned> {
    let c = rcgen::generate_simple_self_signed(vec!["localhost".to_string()])?;
    let cert = CertificateDer::from(c.cert.der().to_vec());
    let key = PrivateKeyDer::try_from(c.key_pair.serialize_der())
        .map_err(|e| anyhow::anyhow!("key: {e}"))?;
    Ok(SelfSigned { cert, key })
}

fn provider() -> Arc<rustls::crypto::CryptoProvider> {
    Arc::new(rustls::crypto::ring::default_provider())
}

/// Server config for the receiver (single self-signed cert, ALPN set). TLS 1.3 only,
/// which both the TCP+TLS path and QUIC (which requires 1.3) share.
pub fn server_config(ss: &SelfSigned) -> Result<Arc<ServerConfig>> {
    let mut cfg = ServerConfig::builder_with_provider(provider())
        .with_protocol_versions(&[&rustls::version::TLS13])?
        .with_no_client_auth()
        .with_single_cert(vec![ss.cert.clone()], ss.key.clone_key())?;
    cfg.alpn_protocols = vec![ALPN.to_vec()];
    Ok(Arc::new(cfg))
}

/// Client config for the sender: verifies nothing (bench), ALPN set, TLS 1.3.
pub fn client_config() -> Arc<ClientConfig> {
    let mut cfg = ClientConfig::builder_with_provider(provider())
        .with_protocol_versions(&[&rustls::version::TLS13])
        .expect("tls13")
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(NoVerify))
        .with_no_client_auth();
    cfg.alpn_protocols = vec![ALPN.to_vec()];
    Arc::new(cfg)
}

/// Accept-any certificate verifier — bench only.
#[derive(Debug)]
struct NoVerify;

impl rustls::client::danger::ServerCertVerifier for NoVerify {
    fn verify_server_cert(
        &self,
        _end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp: &[u8],
        _now: UnixTime,
    ) -> std::result::Result<rustls::client::danger::ServerCertVerified, rustls::Error> {
        Ok(rustls::client::danger::ServerCertVerified::assertion())
    }
    fn verify_tls12_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &rustls::DigitallySignedStruct,
    ) -> std::result::Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error> {
        Ok(rustls::client::danger::HandshakeSignatureValid::assertion())
    }
    fn verify_tls13_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &rustls::DigitallySignedStruct,
    ) -> std::result::Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error> {
        Ok(rustls::client::danger::HandshakeSignatureValid::assertion())
    }
    fn supported_verify_schemes(&self) -> Vec<rustls::SignatureScheme> {
        use rustls::SignatureScheme::*;
        vec![
            RSA_PKCS1_SHA256, RSA_PKCS1_SHA384, RSA_PKCS1_SHA512,
            ECDSA_NISTP256_SHA256, ECDSA_NISTP384_SHA384,
            ED25519, RSA_PSS_SHA256, RSA_PSS_SHA384, RSA_PSS_SHA512,
        ]
    }
}
