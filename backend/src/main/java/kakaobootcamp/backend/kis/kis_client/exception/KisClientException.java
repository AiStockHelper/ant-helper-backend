package kakaobootcamp.backend.kis.kis_client.exception;

public class KisClientException extends RuntimeException {

    public KisClientException(String message){
        super(message);
    }

    public KisClientException(String message, Throwable cause){
        super(message, cause);
    }

}
