# Creating Lambda IAM resource
resource "aws_iam_role" "lambda_iam" {
  name = "lambda-role"

  assume_role_policy = <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Service": "lambda.amazonaws.com"
      },
      "Action": "sts:AssumeRole"
    }
  ]
}
EOF
}

resource "aws_iam_role_policy" "revoke_keys_role_policy" {
  name = "lambda-policy"
  role = aws_iam_role.lambda_iam.id

  policy = <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Action": [
        "s3:*",
        "ses:*",
        "sqs:*",
        "logs:CreateLogGroup",
        "logs:CreateLogStream",
        "logs:PutLogEvents",
        "dynamodb:*"
      ],
      "Effect": "Allow",
      "Resource": "*"
    }
  ]
}
EOF
}

# Creating Lambda resource
resource "aws_lambda_function" "s3_lambda" {
  function_name    = "customer-message"
  role             = aws_iam_role.lambda_iam.arn
  handler          = "com.bartu.s3.S3EventHandler"
  runtime          = var.runtime
  timeout          = 30
  filename         = "../target/lambda.jar"
  source_code_hash = filebase64sha256("../target/lambda.jar")
}

# Creating s3 resource for invoking to lambda function
resource "aws_s3_bucket" "bucket" {
  bucket            = "bucket-customers"
}

# Adding S3 bucket as trigger to my lambda and giving the permissions
resource "aws_s3_bucket_notification" "s3_lambda_trigger" {
  bucket = aws_s3_bucket.bucket.id
  lambda_function {
    lambda_function_arn = aws_lambda_function.s3_lambda.arn
    events              = ["s3:ObjectCreated:*"]

  }
}

# Permission for S3 Lambda
resource "aws_lambda_permission" "s3_lambda_permission" {
  statement_id  = "AllowS3Invoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.s3_lambda.function_name
  principal     = "s3.amazonaws.com"
  source_arn    = "arn:aws:s3:::${aws_s3_bucket.bucket.id}"
}

# Trigger for S3 Lambda
resource "aws_s3_bucket_notification" "bucket_notification" {
  bucket = aws_s3_bucket.bucket.id

  lambda_function {
    lambda_function_arn = aws_lambda_function.s3_lambda.arn
    events              = ["s3:ObjectCreated:*"]
    # filter_prefix       = "AWSLogs/"
    # filter_suffix       = ".log"
  }
  depends_on = [aws_lambda_permission.s3_lambda_permission]
}

# SQS queue to store the data
resource "aws_sqs_queue" "customer_queue" {
  name                        = "customer_queue"
}

# Creating Lambda resource for SQS reading
resource "aws_lambda_function" "sqs_lambda" {
  function_name    = "sqs-reader"
  role             = aws_iam_role.lambda_iam.arn
  handler          = "com.bartu.s3.SQSHandler"
  runtime          = var.runtime
  timeout          = 30
  filename         = "../target/lambda.jar"
  source_code_hash = filebase64sha256("../target/lambda.jar")
}

# Permission for SQS Lambda
resource "aws_lambda_permission" "sqs_lambda_permission" {
  statement_id  = "AllowSQSInvoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.sqs_lambda.function_name
  principal     = "sqs.amazonaws.com"
  source_arn    = "arn:aws:sqs:::*"
}

# Lambda SQS policy
resource "aws_iam_role_policy_attachment" "lambda_sqs_role_policy" {
  role       = aws_iam_role.lambda_iam.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaSQSQueueExecutionRole"
}

# SQS-reader lambda trigger
resource "aws_lambda_event_source_mapping" "event_source_mapping" {
  event_source_arn = aws_sqs_queue.customer_queue.arn
  function_name    = aws_lambda_function.sqs_lambda.arn
}

# Log group for lambda functions
resource "aws_cloudwatch_log_group" "example" {
  name              = "/aws/lambda/customer-message"
  retention_in_days = 14
}

#DynamoDB to store the final data
resource "aws_dynamodb_table" "dynamodb-table" {
  name           = "MESSAGE"
  billing_mode   = "PAY_PER_REQUEST"
  hash_key       = "Id"
  range_key      = "Message"

  attribute {
    name = "Id"
    type = "S"
  }

  attribute {
    name = "Message"
    type = "S"
  }

}
