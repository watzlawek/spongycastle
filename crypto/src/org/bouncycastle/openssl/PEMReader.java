package org.bouncycastle.openssl;

import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.ASN1Object;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.DERInteger;
import org.bouncycastle.asn1.DERObjectIdentifier;
import org.bouncycastle.asn1.cms.ContentInfo;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.sec.ECPrivateKeyStructure;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.RSAPublicKeyStructure;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers;
import org.bouncycastle.crypto.PBEParametersGenerator;
import org.bouncycastle.crypto.generators.OpenSSLPBEParametersGenerator;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.PKCS10CertificationRequest;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;
import org.bouncycastle.util.encoders.Base64;
import org.bouncycastle.util.encoders.Hex;
import org.bouncycastle.x509.X509AttributeCertificate;
import org.bouncycastle.x509.X509V2AttributeCertificate;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.RC2ParameterSpec;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Reader;
import java.security.Key;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.security.spec.AlgorithmParameterSpec;
import java.security.spec.DSAPrivateKeySpec;
import java.security.spec.DSAPublicKeySpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPrivateCrtKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.StringTokenizer;

/**
 * Class for reading OpenSSL PEM encoded streams containing 
 * X509 certificates, PKCS8 encoded keys and PKCS7 objects.
 * <p>
 * In the case of PKCS7 objects the reader will return a CMS ContentInfo object. Keys and
 * Certificates will be returned using the appropriate java.security type.
 */
public class PEMReader extends BufferedReader
{
    private PasswordFinder  pFinder;
    private String          provider;

    /**
     * Create a new PEMReader
     *
     * @param reader the Reader
     */
    public PEMReader(
        Reader reader)
    {
        this(reader, null, "BC");
    }

    /**
     * Create a new PEMReader with a password finder
     *
     * @param reader the Reader
     * @param pFinder the password finder
     */
    public PEMReader(
        Reader          reader,
        PasswordFinder  pFinder)
    {
        this(reader, pFinder, "BC");
    }

    /**
     * Create a new PEMReader with a password finder
     *
     * @param reader the Reader
     * @param pFinder the password finder
     * @param provider the cryptography provider to use
     */
    public PEMReader(
        Reader          reader,
        PasswordFinder  pFinder,
        String          provider)
    {
        super(reader);

        this.pFinder = pFinder;
        this.provider = provider;
    }

    public Object readObject()
        throws IOException
    {
        String  line;

        while ((line = readLine()) != null)
        {
            if (line.indexOf("-----BEGIN PUBLIC KEY") != -1)
            {
                return readPublicKey("-----END PUBLIC KEY");
            }
            if (line.indexOf("-----BEGIN RSA PUBLIC KEY") != -1)
            {
                return readRSAPublicKey("-----END RSA PUBLIC KEY");
            }
            if (line.indexOf("-----BEGIN CERTIFICATE REQUEST") != -1)
            {
                return readCertificateRequest("-----END CERTIFICATE REQUEST");
            }
            if (line.indexOf("-----BEGIN NEW CERTIFICATE REQUEST") != -1)
            {
                return readCertificateRequest("-----END NEW CERTIFICATE REQUEST");
            }
            if (line.indexOf("-----BEGIN CERTIFICATE") != -1)
            {
                return readCertificate("-----END CERTIFICATE");
            }
            if (line.indexOf("-----BEGIN PKCS7") != -1)
            {
               return readPKCS7("-----END PKCS7");
            } 
            if (line.indexOf("-----BEGIN X509 CERTIFICATE") != -1)
            {
                return readCertificate("-----END X509 CERTIFICATE");
            }
            if (line.indexOf("-----BEGIN X509 CRL") != -1)
            {
                return readCRL("-----END X509 CRL");
            }
            if (line.indexOf("-----BEGIN ATTRIBUTE CERTIFICATE") != -1)
            {
                return readAttributeCertificate("-----END ATTRIBUTE CERTIFICATE");
            }
            if (line.indexOf("-----BEGIN RSA PRIVATE KEY") != -1)
            {
                try
                {
                    return readKeyPair("RSA", "-----END RSA PRIVATE KEY");
                }
                catch (Exception e)
                {
                    throw new IOException(
                        "problem creating RSA private key: " + e.toString());
                }
            }
            if (line.indexOf("-----BEGIN DSA PRIVATE KEY") != -1)
            {
                try
                {
                    return readKeyPair("DSA", "-----END DSA PRIVATE KEY");
                }
                catch (Exception e)
                {
                    throw new IOException(
                        "problem creating DSA private key: " + e.toString());
                }
            }
            if (line.indexOf("-----BEGIN EC PARAMETERS-----") != -1)
            {
                return readECParameters("-----END EC PARAMETERS-----");
            }
            if (line.indexOf("-----BEGIN EC PRIVATE KEY-----") != -1)
            {
                return readECPrivateKey("-----END EC PRIVATE KEY-----");
            }
        }

        return null;
    }

    private byte[] readBytes(String endMarker)
        throws IOException
    {
        String          line;
        StringBuffer    buf = new StringBuffer();
  
        while ((line = readLine()) != null)
        {
            if (line.indexOf(endMarker) != -1)
            {
                break;
            }
            buf.append(line.trim());
        }

        if (line == null)
        {
            throw new IOException(endMarker + " not found");
        }

        return Base64.decode(buf.toString());
    }

    private PublicKey readRSAPublicKey(String endMarker) 
        throws IOException 
    {
        ByteArrayInputStream bAIS = new ByteArrayInputStream(readBytes(endMarker));
        ASN1InputStream ais = new ASN1InputStream(bAIS);
        Object asnObject = ais.readObject();
        ASN1Sequence sequence = (ASN1Sequence) asnObject;
        RSAPublicKeyStructure rsaPubStructure = new RSAPublicKeyStructure(sequence);
        RSAPublicKeySpec keySpec = new RSAPublicKeySpec(
                    rsaPubStructure.getModulus(), 
                    rsaPubStructure.getPublicExponent());

        try 
        {
            KeyFactory keyFact = KeyFactory.getInstance("RSA", provider);

            return keyFact.generatePublic(keySpec);
        }
        catch (NoSuchProviderException e)
        {
            throw new IOException("can't find provider " + provider);
        }
        catch (Exception e)
        {
            throw new IOException("problem extracting key: " + e.toString());
        }
    }

    private PublicKey readPublicKey(String endMarker)
        throws IOException
    {
        KeySpec keySpec = new X509EncodedKeySpec(readBytes(endMarker));
        String[] algorithms = { "DSA", "RSA" };
        for (int i = 0; i < algorithms.length; i++) 
        {
            try 
            {
                KeyFactory keyFact = KeyFactory.getInstance(algorithms[i],
                                provider);
                PublicKey pubKey = keyFact.generatePublic(keySpec);
                
                return pubKey;
            }
            catch (NoSuchAlgorithmException e) 
            { 
                // ignore
            }
            catch (InvalidKeySpecException e) 
            { 
                // ignore
            }
            catch (NoSuchProviderException e)
            {
                throw new RuntimeException("can't find provider " + provider);
            }
        }
        
        return null;
    }

    /**
     * Reads in a X509Certificate.
     *
     * @return the X509Certificate
     * @throws IOException if an I/O error occured
     */
    private X509Certificate readCertificate(
        String  endMarker)
        throws IOException
    {
        ByteArrayInputStream    bIn = new ByteArrayInputStream(readBytes(endMarker));

        try
        {
            CertificateFactory certFact
                    = CertificateFactory.getInstance("X.509", provider);

            return (X509Certificate)certFact.generateCertificate(bIn);
        }
        catch (Exception e)
        {
            throw new IOException("problem parsing cert: " + e.toString());
        }
    }

    /**
     * Reads in a X509CRL.
     *
     * @return the X509Certificate
     * @throws IOException if an I/O error occured
     */
    private X509CRL readCRL(
        String  endMarker)
        throws IOException
    {
        ByteArrayInputStream    bIn = new ByteArrayInputStream(readBytes(endMarker));

        try
        {
            CertificateFactory certFact
                    = CertificateFactory.getInstance("X.509", provider);

            return (X509CRL)certFact.generateCRL(bIn);
        }
        catch (Exception e)
        {
            throw new IOException("problem parsing cert: " + e.toString());
        }
    }

    /**
     * Reads in a PKCS10 certification request.
     *
     * @return the certificate request.
     * @throws IOException if an I/O error occured
     */
    private PKCS10CertificationRequest readCertificateRequest(
        String  endMarker)
        throws IOException
    {
        try
        {
            return new PKCS10CertificationRequest(readBytes(endMarker));
        }
        catch (Exception e)
        {
            throw new IOException("problem parsing cert: " + e.toString());
        }
    }

    /**
     * Reads in a X509 Attribute Certificate.
     *
     * @return the X509 Attribute Certificate
     * @throws IOException if an I/O error occured
     */
    private X509AttributeCertificate readAttributeCertificate(
        String  endMarker)
        throws IOException
    {
        return new X509V2AttributeCertificate(readBytes(endMarker));
    }
    
    /**
     * Reads in a PKCS7 object. This returns a ContentInfo object suitable for use with the CMS
     * API.
     *
     * @return the X509Certificate
     * @throws IOException if an I/O error occured
     */
    private ContentInfo readPKCS7(
        String  endMarker)
        throws IOException
    {
        String                                  line;
        StringBuffer                        buf = new StringBuffer();
        ByteArrayOutputStream    bOut = new ByteArrayOutputStream();
  
        while ((line = readLine()) != null)
        {
            if (line.indexOf(endMarker) != -1)
            {
                break;
            }
            
            line = line.trim();
            
            buf.append(line.trim());
            
            Base64.decode(buf.substring(0, (buf.length() / 4) * 4), bOut);

            buf.delete(0, (buf.length() / 4) * 4);
        }

        if (buf.length() != 0)
        {
            throw new RuntimeException("base64 data appears to be truncated");
        }
        
        if (line == null)
        {
            throw new IOException(endMarker + " not found");
        }

        ByteArrayInputStream    bIn = new ByteArrayInputStream(bOut.toByteArray());

        try
        {
            ASN1InputStream aIn = new ASN1InputStream(bIn);

            return ContentInfo.getInstance(aIn.readObject());
        }
        catch (Exception e)
        {
            throw new IOException("problem parsing PKCS7 object: " + e.toString());
        }
    }

    private SecretKey getKey(
        String  algorithm,
        int     keyLength,
        byte[]  salt)
        throws IOException
    {
        return getKey(algorithm, keyLength, salt, false);
    }


    /**
     * create the secret key needed for this object, fetching the password
     */
    private SecretKey getKey(
        String  algorithm,
        int     keyLength,
        byte[]  salt,
        boolean des2)
        throws IOException
    {
        if (pFinder == null)
        {
            throw new IOException("No password finder specified, but a password is required");
        }

        char[]      password = pFinder.getPassword();

        if (password == null)
        {
            throw new IOException("Password is null, but a password is required");
        }
        
        OpenSSLPBEParametersGenerator   pGen = new OpenSSLPBEParametersGenerator();

        pGen.init(PBEParametersGenerator.PKCS5PasswordToBytes(password), salt);

        KeyParameter keyParam;
        keyParam = (KeyParameter) pGen.generateDerivedParameters(keyLength * 8);
        byte[] key = keyParam.getKey();
        if (des2 && key.length >= 24)
        {
            // For DES2, we must copy first 8 bytes into the last 8 bytes.
            System.arraycopy(key, 0, key, 16, 8);
        }
        return new javax.crypto.spec.SecretKeySpec(key, algorithm);
    }

    /**
     * Read a Key Pair
     */
    private KeyPair readKeyPair(
        String  type,
        String  endMarker)
        throws Exception
    {
        boolean         isEncrypted = false;
        String          line = null;
        String          dekInfo = null;
        StringBuffer    buf = new StringBuffer();

        while ((line = readLine()) != null)
        {
            if (line.startsWith("Proc-Type: 4,ENCRYPTED"))
            {
                isEncrypted = true;
            }
            else if (line.startsWith("DEK-Info:"))
            {
                dekInfo = line.substring(10);
            }
            else if (line.indexOf(endMarker) != -1)
            {
                break;
            }
            else
            {
                buf.append(line.trim());
            }
        }

        //
        // extract the key
        //
        byte[]  keyBytes = null;

        if (isEncrypted)
        {
            StringTokenizer        tknz = new StringTokenizer(dekInfo, ",");
            String                 encoding = tknz.nextToken();
            byte[]                 iv = Hex.decode(tknz.nextToken());
            AlgorithmParameterSpec paramSpec = new IvParameterSpec(iv);
            String                 alg;
            String                 blockMode = "CBC";
            String                 padding = "PKCS5Padding";
            Key                    sKey;


            // Figure out block mode and padding.
            if (encoding.endsWith("-CFB"))
            {
                blockMode = "CFB";
                padding = "NoPadding";
            }
            if (encoding.endsWith("-ECB") ||
                "DES-EDE".equals(encoding) ||
                "DES-EDE3".equals(encoding))
            {
                // ECB is actually the default (though seldom used) when OpenSSL
                // uses DES-EDE (des2) or DES-EDE3 (des3).
                blockMode = "ECB";
                paramSpec = null;
            }
            if (encoding.endsWith("-OFB"))
            {
                blockMode = "OFB";
                padding = "NoPadding";
            }


            // Figure out algorithm and key size.
            if (encoding.startsWith("DES-EDE"))
            {
                alg = "DESede";
                // "DES-EDE" is actually des2 in OpenSSL-speak!
                // "DES-EDE3" is des3.
                boolean des2 = !encoding.startsWith("DES-EDE3");
                sKey = getKey(alg, 24, iv, des2);
            }
            else if (encoding.startsWith("DES-"))
            {
                alg = "DES";
                sKey = getKey(alg, 8, iv);
            }
            else if (encoding.startsWith("BF-"))
            {
                alg = "Blowfish";
                sKey = getKey(alg, 16, iv);
            }
            else if (encoding.startsWith("RC2-"))
            {
                alg = "RC2";
                int keyBits = 128;
                if (encoding.startsWith("RC2-40-"))
                {
                    keyBits = 40;
                }
                else if (encoding.startsWith("RC2-64-"))
                {
                    keyBits = 64;
                }
                sKey = getKey(alg, keyBits / 8, iv);
                if (paramSpec == null) // ECB block mode
                {
                    paramSpec = new RC2ParameterSpec(keyBits);
                }
                else
                {
                    paramSpec = new RC2ParameterSpec(keyBits, iv);
                }
            }
            else if (encoding.startsWith("AES-"))
            {
                alg = "AES";
                // TODO Should this be handled automatically by getKey(AES)/OpenSSL?
                byte[] salt = iv;
                if (salt.length > 8)
                {
                    salt = new byte[8];
                    System.arraycopy(iv, 0, salt, 0, 8);
                }

                int keyBits;
                if (encoding.startsWith("AES-128-"))
                {
                    keyBits = 128;
                }
                else if (encoding.startsWith("AES-192-"))
                {
                    keyBits = 192;
                }
                else if (encoding.startsWith("AES-256-"))
                {
                    keyBits = 256;
                }
                else
                {
                    throw new IOException("unknown AES encryption with private key");
                }
                sKey = getKey("AES", keyBits / 8, salt);
            }
            else
            {
                throw new IOException("unknown encryption with private key");
            }

            String transformation = alg + "/" + blockMode + "/" + padding;
            Cipher c = Cipher.getInstance(transformation, provider);
            if (paramSpec == null) // ECB block mode
            {
                c.init(Cipher.DECRYPT_MODE, sKey);
            }
            else
            {
                c.init(Cipher.DECRYPT_MODE, sKey, paramSpec);
            }
            keyBytes = c.doFinal(Base64.decode(buf.toString()));
        }
        else
        {
            keyBytes = Base64.decode(buf.toString());
        }

        KeySpec                 pubSpec, privSpec;
        ByteArrayInputStream    bIn = new ByteArrayInputStream(keyBytes);
        ASN1InputStream         aIn = new ASN1InputStream(bIn);
        ASN1Sequence            seq = (ASN1Sequence)aIn.readObject();

        if (type.equals("RSA"))
        {
            DERInteger              v = (DERInteger)seq.getObjectAt(0);
            DERInteger              mod = (DERInteger)seq.getObjectAt(1);
            DERInteger              pubExp = (DERInteger)seq.getObjectAt(2);
            DERInteger              privExp = (DERInteger)seq.getObjectAt(3);
            DERInteger              p1 = (DERInteger)seq.getObjectAt(4);
            DERInteger              p2 = (DERInteger)seq.getObjectAt(5);
            DERInteger              exp1 = (DERInteger)seq.getObjectAt(6);
            DERInteger              exp2 = (DERInteger)seq.getObjectAt(7);
            DERInteger              crtCoef = (DERInteger)seq.getObjectAt(8);

            pubSpec = new RSAPublicKeySpec(
                        mod.getValue(), pubExp.getValue());
            privSpec = new RSAPrivateCrtKeySpec(
                    mod.getValue(), pubExp.getValue(), privExp.getValue(),
                    p1.getValue(), p2.getValue(),
                    exp1.getValue(), exp2.getValue(),
                    crtCoef.getValue());
        }
        else    // "DSA"
        {
            DERInteger              v = (DERInteger)seq.getObjectAt(0);
            DERInteger              p = (DERInteger)seq.getObjectAt(1);
            DERInteger              q = (DERInteger)seq.getObjectAt(2);
            DERInteger              g = (DERInteger)seq.getObjectAt(3);
            DERInteger              y = (DERInteger)seq.getObjectAt(4);
            DERInteger              x = (DERInteger)seq.getObjectAt(5);

            privSpec = new DSAPrivateKeySpec(
                        x.getValue(), p.getValue(),
                            q.getValue(), g.getValue());
            pubSpec = new DSAPublicKeySpec(
                        y.getValue(), p.getValue(),
                            q.getValue(), g.getValue());
        }

        KeyFactory          fact = KeyFactory.getInstance(type, provider);

        return new KeyPair(
                    fact.generatePublic(pubSpec),
                    fact.generatePrivate(privSpec));
    }

    private ECNamedCurveParameterSpec readECParameters(String endMarker)
        throws IOException
    {
        DERObjectIdentifier oid = (DERObjectIdentifier)ASN1Object.fromByteArray(readBytes(endMarker));

        return ECNamedCurveTable.getParameterSpec(oid.getId());
    }

    private KeyPair readECPrivateKey(String endMarker)
        throws IOException
    {
        try
        {
            ECPrivateKeyStructure pKey = new ECPrivateKeyStructure((ASN1Sequence)ASN1Object.fromByteArray(readBytes(endMarker)));
            AlgorithmIdentifier   algId = new AlgorithmIdentifier(X9ObjectIdentifiers.id_ecPublicKey, pKey.getParameters());
            PrivateKeyInfo        privInfo = new PrivateKeyInfo(algId, pKey.getDERObject());
            SubjectPublicKeyInfo  pubInfo = new SubjectPublicKeyInfo(algId, pKey.getPublicKey().getBytes());
            PKCS8EncodedKeySpec   privSpec = new PKCS8EncodedKeySpec(privInfo.getEncoded());
            X509EncodedKeySpec    pubSpec = new X509EncodedKeySpec(pubInfo.getEncoded());
            KeyFactory            fact = KeyFactory.getInstance("ECDSA", provider);

            return new KeyPair(fact.generatePublic(pubSpec), fact.generatePrivate(privSpec));
        }
        catch (ClassCastException e)
        { 
            throw new IOException("wrong ASN.1 object found in stream");
        }
        catch (Exception e)
        {
            throw new IOException("problem parsing EC private key: " + e);
        }
    }
}
