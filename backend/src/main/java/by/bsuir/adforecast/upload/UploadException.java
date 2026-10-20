package by.bsuir.adforecast.upload;

import java.util.List;

public class UploadException extends RuntimeException {

    private final List<String> errors;

    public UploadException(List<String> errors) {
        super(String.join("; ", errors));
        this.errors = List.copyOf(errors);
    }

    public List<String> getErrors() {
        return errors;
    }
}
